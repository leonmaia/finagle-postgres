package com.twitter.finagle.postgres.codec

import com.twitter.finagle._
import com.twitter.finagle.postgres.ResultSet
import com.twitter.finagle.postgres.connection.Connection
import com.twitter.finagle.postgres.messages._
import com.twitter.finagle.postgres.values.Md5Encryptor
import com.twitter.logging.Logger
import com.twitter.util.Future

import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.frame.FrameDecoder

import scala.collection.mutable

/*
 * Postgres codec implementation.
 *
 * Used by client to encode requests and parse responses.
 */
class PgCodec(
    user: String,
    password: Option[String],
    database: String,
    id: String) extends CodecFactory[PgRequest, PgResponse] {
  def server = throw new UnsupportedOperationException("client only")

  def client = Function.const {
    new Codec[PgRequest, PgResponse] {
      def pipelineFactory = new ChannelPipelineFactory {
        def getPipeline = {
          val pipeline = Channels.pipeline()
          pipeline.addLast("binary_to_packet", new PacketDecoder)
          pipeline.addLast("packet_to_backend_messages", new BackendMessageDecoder(new BackendMessageParser))
          pipeline.addLast("backend_messages_to_postgres_response", new PgClientChannelHandler())
          pipeline
        }
      }

      override def prepareConnFactory(underlying: ServiceFactory[PgRequest, PgResponse]) = {
        val errorHandling = new HandleErrorsProxy(underlying)
        new AuthenticationProxy(errorHandling, user, password, database)
      }

      override def prepareServiceFactory(underlying: ServiceFactory[PgRequest, PgResponse]) = {
        new CustomOIDProxy(underlying, id)
      }
    }
  }
}

/*
 * Filter that converts exceptions into ServerErrors.
 */
class HandleErrorsProxy(
    delegate: ServiceFactory[PgRequest, PgResponse]) extends ServiceFactoryProxy(delegate) {

  override def apply(conn: ClientConnection): Future[Service[PgRequest, PgResponse]] = {
    for {
      service <- delegate.apply(conn)
    } yield HandleErrors.andThen(service)
  }

  object HandleErrors extends SimpleFilter[PgRequest, PgResponse] {
    def apply(request: PgRequest, service: Service[PgRequest, PgResponse]) = {
      service.apply(request).flatMap {
        case Error(details) =>
          Future.exception(Errors.server("%s\n%s".format(request.toString(), details.getOrElse(""))))
        case r => Future.value(r)
      }
    }
  }
}

/*
 * Filter that does password authentication before issuing requests.
 */
class AuthenticationProxy(
    delegate: ServiceFactory[PgRequest, PgResponse],
    user: String, password: Option[String],
    database: String) extends ServiceFactoryProxy(delegate) {
  private val logger = Logger(getClass.getName)

  override def apply(conn: ClientConnection): Future[Service[PgRequest, PgResponse]] = {
    for {
      service <- delegate.apply(conn)
      startupResponse <- service(PgRequest(new StartupMessage(user, database)))
      passwordResponse <- sendPassword(startupResponse, service)
      _ <- verifyResponse(passwordResponse)
    } yield service
  }

  private[this] def sendPassword(
      startupResponse: PgResponse, service: Service[PgRequest, PgResponse]): Future[PgResponse] = {
    logger.ifDebug("Startup response: %s".format(startupResponse))

    startupResponse match {
      case PasswordRequired(encoding) => password match {
        case Some(pass) =>
          val msg = encoding match {
            case ClearText => PasswordMessage(pass)
            case Md5(salt) => PasswordMessage(new String(Md5Encryptor.encrypt(user.getBytes, pass.getBytes, salt)))
          }
          service(PgRequest(msg))

        case None => Future.exception(Errors.client("Password has to be specified for authenticated connection"))
      }

      case r => Future.value(r)
    }
  }

  private[this] def verifyResponse(response: PgResponse): Future[Unit] = {
    response match {
      case AuthenticatedResponse(statuses, processId, secretKey) =>
        logger.ifDebug("Authenticated: %d %d\n%s".format(processId, secretKey, statuses))
        Future(Unit)
    }
  }
}

object CustomOIDProxy {
  val serviceOIDMap = new mutable.HashMap[String, Map[String, String]]()
}

/*
 * Filter for handling custom types in responses.
 */
class CustomOIDProxy(
    delegate: ServiceFactory[PgRequest, PgResponse], id:String) extends ServiceFactoryProxy(delegate) {
  val customTypes = """
    |SELECT      t.typname as type, t.oid as oid
    |FROM        pg_type t
    |LEFT JOIN   pg_catalog.pg_namespace n ON n.oid = t.typnamespace
    |WHERE       (t.typrelid = 0 OR (SELECT c.relkind = 'c' FROM pg_catalog.pg_class c WHERE c.oid = t.typrelid))
    |AND         NOT EXISTS(SELECT 1 FROM pg_catalog.pg_type el WHERE el.oid = t.typelem AND el.typarray = t.oid)
    |AND         n.nspname NOT IN ('pg_catalog', 'information_schema')
  """.stripMargin

  override def apply(conn: ClientConnection): Future[Service[PgRequest, PgResponse]] = {
    for {
      service <- delegate.apply(conn)
      typeResponse <- service(new PgRequest(new Query(customTypes)))
      _ <- handleTypeResponse(typeResponse)
    } yield service
  }

  def handleTypeResponse(response:PgResponse):Future[Unit] = {
    val result:ResultSet = response match {
      case SelectResult(fields, rows) => ResultSet(fields, rows, Map())
      case _ => throw Errors.client("Expected a SelectResult")
    }

    val typeMap:Map[String, String] = result.rows.map { row =>
      (row.get[String]("oid"), row.get[String]("type"))
    }.toMap

    CustomOIDProxy.serviceOIDMap += id -> typeMap

    Future(Unit)
  }
}

/*
 * Decodes a Packet into a BackendMessage.
 */
class BackendMessageDecoder(val parser: BackendMessageParser) extends SimpleChannelHandler {
  private val logger = Logger(getClass.getName)

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val message = e.getMessage

    message match {
      case packet: Packet =>
        parser.parse(packet) match {
          case Some(backendMessage) =>
            Channels.fireMessageReceived(ctx, backendMessage)
          case None =>
            logger.warning("Cannot parse the packet. Disconnecting...")
            Channels.disconnect(ctx.getChannel)
        }

      case _ =>
        logger.warning("Only packet is supported...")
        Channels.disconnect(ctx.getChannel)
    }
  }
}

/*
 * Decodes a byte stream into a Packet.
 */
class PacketDecoder extends FrameDecoder {
  private val logger = Logger(getClass.getName)

  def decode(ctx: ChannelHandlerContext, channel: Channel, buffer: ChannelBuffer): AnyRef = {
    if (buffer.readableBytes() < 5) {
      return null
    }

    buffer.markReaderIndex()
    val code: Char = buffer.readByte().asInstanceOf[Char]
    val totalLength = buffer.readInt()
    val length = totalLength - 4

    if (buffer.readableBytes() < length) {
      buffer.resetReaderIndex()
      return null
    }

    new Packet(Some(code), totalLength, buffer.readSlice(length))
  }
}

/*
 * Map PgRequest to PgResponse.
 */
class PgClientChannelHandler extends SimpleChannelHandler {
  private[this] val logger = Logger(getClass.getName)

  private[this] val connection = new Connection()

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val message = e.getMessage

    message match {
      case msg: BackendMessage =>
        connection.receive(msg).map {
          Channels.fireMessageReceived(ctx, _)
        }

      case unsupported =>
        logger.warning("Only backend message is supported...")
        Channels.disconnect(ctx.getChannel)
    }
  }

  override def writeRequested(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val buf = event.getMessage match {
      case PgRequest(msg, flush) =>
        val packet = msg.asPacket()
        val c = ChannelBuffers.dynamicBuffer()

        c.writeBytes(packet.encode)

        if (flush) {
          c.writeBytes(Flush.asPacket.encode)
        }

        connection.send(msg)
        c
      case _ =>
        logger.warning("Cannot convert message... Skipping")
        event.getMessage
    }

    Channels.write(ctx, event.getFuture, buf, event.getRemoteAddress)
  }
}





