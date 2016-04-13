package org.stingray.contester.rpc4

import java.io.{Closeable, InputStream}
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

import com.trueaccord.scalapb.GeneratedMessage
import com.twitter.io.Charsets
import com.twitter.util.{Future, Promise, Return, Try}
import grizzled.slf4j.Logging
import io.netty.buffer.{ByteBuf, ByteBufInputStream, ByteBufOutputStream, Unpooled}
import io.netty.channel._
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.util.{ReferenceCountUtil, ReferenceCounted}
import org.stingray.contester.rpc4.proto.Header

/** Connected server registry. Will be called for connected and disconnected channels.
  *
  */
trait Registry {
  /** This gets called when new server reverse-connects to the dispatcher.
    *
    * @param client Client for connected channel.
    */
  def register(client: RpcClient): Unit

  /** This gets called when channel is ejected from the dispatcher.
    *
    * @param client Client instance.
    */
  def unregister(client: RpcClient): Unit
}

case class ChannelDisconnectedException(reason: Option[Throwable]=None) extends Throwable

object DefaultChannelDisconnectedException extends ChannelDisconnectedException

/** Error in the remote server.
  *
  * @param value String passed as error description.
  */
case class RemoteError(value: String) extends RuntimeException(value)

case class UnexpectedMessageTypeError(messageType: Header.MessageType) extends RuntimeException(messageType.name)

/** Dispatcher's pipeline factory. Will produce a pipeline that speaks rpc4 and connects those to the registry.
  *
  * @param registry Where do we register our channels.
  */
class ServerPipelineFactory[C <: Channel](registry: Registry) extends ChannelInitializer[C] {
  override def initChannel(ch: C): Unit = {
    val pipeline = ch.pipeline()
    pipeline.addFirst("RpcClient", new RpcClientImpl[C](ch, registry))
    pipeline.addFirst("RpcDecoder", new RpcFramerDecoder)
    pipeline.addFirst("FrameDecoder", Framer.simpleFrameDecoder)
  }
}

object Framer {
  val simpleFrameDecoder = new LengthFieldBasedFrameDecoder(ByteOrder.BIG_ENDIAN, 64 * 1024 * 1024, 0, 4, 0, 4, true)
  // val simpleFrameEncoder = new LengthFieldPrepender(ByteOrder.BIG_ENDIAN, 4, 0, false)
}

/** Decoded messages, contain header and optionally payload.
  *
  * @param header
  * @param payload
  */
case class Rpc4Tuple(header: Header, payload: Option[ByteBuf]=None)

/** Decoder. A message has a header and optionally a payload.
  *
  */
private class RpcFramerDecoder extends SimpleChannelInboundHandler[ByteBuf] {
  private[this] var storedHeader: Option[Header] = None

  override def channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf): Unit =
      if (storedHeader.isEmpty) {
        val header = try {
          val stream = new ByteBufInputStream(msg)
          try {
            Header.parseFrom(stream)
          } finally {
            stream.close()
          }
        } finally {
          ReferenceCountUtil.release(msg)
        }
        if (header.getPayloadPresent) {
          storedHeader = Some(header)
        } else {
          ctx.fireChannelRead(Rpc4Tuple(header))
        }
      } else {
        val header = storedHeader.get
        storedHeader = None

        ctx.fireChannelRead(Rpc4Tuple(header, Some(msg)))
      }
}

trait RpcClient {
  type Deserializer[T] = (ByteBufInputStream) => T

  def callFull[T](methodName: String, payload: Option[GeneratedMessage],
              deserializer: Option[Deserializer[T]]): Future[Option[T]]

  def call[T](methodName: String, payload: GeneratedMessage, deserializer: (ByteBufInputStream) => T): Future[T] =
    callFull(methodName, Some(payload), Some(deserializer)).map(_.get)

  def callNoResult(methodName: String, payload: GeneratedMessage): Future[Unit] =
    callFull(methodName, Some(payload), None).unit
}

case class RpcTuple1(header: Header, payload: Option[ByteBuf])

/** RPC Client over the channel given.
  * Offers a Future-based call interface.
  *
  * @param channel Channel to work on.
  */
class RpcClientImpl[C <: Channel](channel: C, registry: Registry) extends SimpleChannelInboundHandler[ByteBuf] with RpcClient with Logging {
  // todo: implement org.stingray.contester.rpc4.RpcClient.exceptionCaught()

  //trace("Creating new rpc client for channel %s".format(channel))

  private[this] val requests = {
    import scala.collection.JavaConverters._
    new ConcurrentHashMap[Int, Promise[RpcTuple1]]().asScala
  }
  private[this] val sequenceNumber = new AtomicInteger
  private[this] val disconnected = new AtomicBoolean

  private[this] val storedHeader = new AtomicReference[Option[Header]](None)

  private class WriteHandler(requestId: Int) extends ChannelFutureListener {
    def operationComplete(p1: ChannelFuture): Unit = {
      if (!p1.isSuccess) {
        killRequest(requestId)
      }
    }
  }

  private def killRequest(requestId: Int, e: Option[Throwable]=None): Unit =
    requests.remove(requestId).foreach(_.setException(DefaultChannelDisconnectedException))

  private def withLength(m: GeneratedMessage) = {
    val b = Unpooled.buffer(m.serializedSize + 4)
    val wr = new ByteBufOutputStream(b)
    try {
      wr.writeInt(m.serializedSize)
      m.writeTo(wr)
    } finally {
      wr.close()
    }
    b
  }

  def callFull[T](methodName: String, payload: Option[GeneratedMessage], deserializer: Option[Deserializer[T]]): Future[Option[T]] = {
    if (disconnected.get())
      Future.exception(DefaultChannelDisconnectedException)
    else {
      trace(s"Call: $methodName($payload)")
      val resultPromise = new Promise[RpcTuple1]
      val requestId = sequenceNumber.getAndIncrement
      val header = Header(sequence = Some(requestId), messageType = Some(Header.MessageType.REQUEST),
        method = Some(methodName), payloadPresent = Some(payload.isDefined))

      requests.put(requestId, resultPromise)

      val headerPart = withLength(header)
      val msg = payload.map { p =>
        Unpooled.wrappedBuffer(headerPart, withLength(p))
      }.getOrElse(headerPart)

      try {
        channel.writeAndFlush(msg).addListener(new WriteHandler(requestId)) // handle cancel
      } catch {
        case e: Throwable =>
          killRequest(requestId, Some(e))
      }

      resultPromise.flatMap { rt =>
        try {
          rt.header.getMessageType match {
            case Header.MessageType.ERROR =>
              Future.exception(new RemoteError(rt.payload.map(_.toString(Charsets.Utf8)).getOrElse("Unknown")))
            case Header.MessageType.RESPONSE =>
              Future.value(deserializer.flatMap { d =>
                rt.payload.map(p => parseWith(p, d))
              })
            case x =>
              Future.exception(UnexpectedMessageTypeError(x))
          }
        } finally {
          rt.payload.foreach(ReferenceCountUtil.release)
        }
      }.onSuccess { r =>
        trace(s"Result($methodName): $r")
      }.onFailure(error(s"Error($methodName)", _))
    }
  }

  def messageReceived(header: Header, payload: Option[ByteBuf]) =
    requests.remove(header.getSequence.toInt) match {
      case None =>
        payload.foreach(ReferenceCountUtil.release)
      case Some(p) =>
        header.getMessageType match {
          case Header.MessageType.ERROR | Header.MessageType.RESPONSE =>
            p.setValue(RpcTuple1(header, payload))
          case _ => payload.foreach(ReferenceCountUtil.release)
        }
    }

  private def parseWith[T](msg: ByteBuf, d: (ByteBufInputStream) => T): T = {
    val stream = new ByteBufInputStream(msg)
    try {
      d(stream)
    } finally {
      stream.close()
    }
  }

  private def parseHeader(msg: ByteBuf): Header =
    try {
      parseWith(msg, Header.parseFrom)
    } finally {
      ReferenceCountUtil.release(msg)
    }

  override def channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf): Unit =
    storedHeader.getAndSet(None) match {
      case Some(header) =>
        messageReceived(header, Some(msg))
      case None =>
        storedHeader.set(Some(parseHeader(msg)))
    }

  override def channelUnregistered(ctx: ChannelHandlerContext): Unit = {
    registry.unregister(this)
    disconnected.set(true)
    while (requests.nonEmpty) {
      requests.keys.foreach(killRequest(_))
    }
    registry.unregister(this)
    super.channelUnregistered(ctx)
  }

  override def channelRegistered(ctx: ChannelHandlerContext): Unit = {
    registry.register(this)
    super.channelRegistered(ctx)
  }
}
