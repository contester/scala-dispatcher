package org.stingray.contester.rpc4

import java.io.InputStream
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import com.trueaccord.scalapb.GeneratedMessage
import com.twitter.io.Charsets
import com.twitter.util.{Future, Promise}
import grizzled.slf4j.Logging
import io.netty.buffer.{ByteBuf, ByteBufInputStream, ByteBufOutputStream, Unpooled}
import io.netty.channel._
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.util.ReferenceCountUtil
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
class RemoteError(value: String) extends RuntimeException(value)

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
  type Deserializer[T] = InputStream => T

  def call[T](methodName: String, payload: Option[GeneratedMessage],
              deserializer: Option[Deserializer[T]]): Future[Option[T]]

  def call[T](methodName: String, payload: GeneratedMessage, deserializer: Deserializer[T]): Future[T] =
    call(methodName, Some(payload), Some(deserializer)).map(_.get)

  def callNoResult(methodName: String, payload: GeneratedMessage): Future[Unit] =
    call(methodName, Some(payload), None).unit
}

/** RPC Client over the channel given.
  * Offers a Future-based call interface.
  *
  * @param channel Channel to work on.
  */
class RpcClientImpl[C <: Channel](channel: C, registry: Registry) extends SimpleChannelInboundHandler[Rpc4Tuple] with RpcClient with Logging {
  // todo: implement org.stingray.contester.rpc4.RpcClient.exceptionCaught()

  //trace("Creating new rpc client for channel %s".format(channel))

  private[this] val requests = {
    import scala.collection.JavaConverters._
    new ConcurrentHashMap[Int, Promise[Option[ByteBuf]]]().asScala
  }
  private[this] val sequenceNumber = new AtomicInteger
  private[this] val disconnected = new AtomicBoolean

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

  def call[T](methodName: String, payload: Option[GeneratedMessage], deserializer: Option[Deserializer[T]]): Future[Option[T]] = {
    if (disconnected.get())
      Future.exception(DefaultChannelDisconnectedException)
    else {
      trace(s"Call: $methodName($payload)")
      val result = new Promise[Option[ByteBuf]]
      val requestId = sequenceNumber.getAndIncrement
      val header = Header(sequence = Some(requestId), messageType = Some(Header.MessageType.REQUEST),
        method = Some(methodName), payloadPresent = Some(payload.isDefined))
      requests.put(requestId, result)

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

      deserializer.map { d =>
        result.map { optr =>
          optr.map { r =>
            try {
              val rs = new ByteBufInputStream(r)
              try {
                d(rs)
              } finally {
                rs.close()
              }
            } finally {
              ReferenceCountUtil.release(r)
            }
          }
        }
      }.getOrElse(result.map(_.foreach(ReferenceCountUtil.release)).map(_ => None))
        .onSuccess { r =>
          trace(s"Result($methodName): $r")
        }.onFailure(error(s"Error($methodName)", _))
    }
  }

  override def channelRead0(ctx: ChannelHandlerContext, msg: Rpc4Tuple): Unit =
    msg.header.messageType match {
      case Some(Header.MessageType.RESPONSE) =>
        requests.remove(msg.header.sequence.get.toInt).foreach { h =>
          h.setValue(msg.payload)
        }
      case Some(Header.MessageType.ERROR) =>
        requests.remove(msg.header.sequence.get.toInt).foreach { h =>
          h.setException(new RemoteError(msg.payload.map(_.toString(Charsets.Utf8)).getOrElse("Unspecified error")))
          msg.payload.foreach(ReferenceCountUtil.release)
        }
      case _ => super.channelRead0(ctx, msg)
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
