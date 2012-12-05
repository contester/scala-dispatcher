package org.stingray.contester.rpc4

import actors.threadpool.AtomicInteger
import collection.mutable.HashMap
import com.google.protobuf.MessageLite
import com.twitter.util.Promise
import org.jboss.netty.buffer.{ChannelBuffers, ChannelBuffer}
import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.frame.FrameDecoder
import proto.RpcFour
import org.stingray.contester.utils.ProtobufTools

trait Registry {
  def register(channel: Channel): ChannelHandler
  def unregister(channel: Channel): Unit
}


class ChannelDisconnectedException(reason: scala.Throwable) extends scala.Throwable(reason) {
  def this() =
    this(new Throwable)
}
class RemoteError(value: String) extends scala.Throwable(value)

class ServerPipelineFactory(registry: Registry) extends ChannelPipelineFactory {
  def getPipeline = {
    val result = Channels.pipeline()
    result.addFirst("Registerer", new RpcRegisterer(registry))
    result.addFirst("RpcDecoder", new RpcFramerDecoder)
    result.addFirst("RpcEncoder", new RpcFramerEncoder)
    result.addFirst("FrameDecoder", new SimpleFramer)

    result
  }
}

private class SimpleFramer extends FrameDecoder {
  def decode(ctx: ChannelHandlerContext, chan: Channel, buf: ChannelBuffer) = {
    if (buf.readableBytes() > 4) {
      buf.markReaderIndex()
      val length = buf.readInt()
      if (buf.readableBytes() < length) {
        buf.resetReaderIndex()
        null
      } else {
        buf.readBytes(length)
      }
    } else null
  }
}

private class Rpc4Tuple(val header: RpcFour.Header, val payload: Option[Array[Byte]])

private class RpcFramerDecoder extends SimpleChannelUpstreamHandler {
  private[this] var storedHeader: Option[RpcFour.Header] = None

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent): Unit = {
    val frame = e.getMessage.asInstanceOf[ChannelBuffer]
    storedHeader.synchronized {
      if (storedHeader.isEmpty) {
        val header = RpcFour.Header.parseFrom(frame.array())
        if (header.getPayloadPresent) {
          storedHeader = Some(header)
        } else {
          Channels.fireMessageReceived(ctx, new Rpc4Tuple(header, None))
        }
      } else {
        val header = storedHeader.get
        storedHeader = None
        Channels.fireMessageReceived(ctx, new Rpc4Tuple(header, Some(frame.array())))
      }
    }
  }
}


private class RpcFramerEncoder extends SimpleChannelDownstreamHandler {
  private[this] class JustReturnListener(e: MessageEvent) extends ChannelFutureListener {
    def operationComplete(p1: ChannelFuture) {
      if (p1.isSuccess) {
        e.getFuture.setSuccess()
      } else e.getFuture.setFailure(p1.getCause)
    }
  }

  private[this] def withLength(channel: Channel, x: Array[Byte]) = {
    val b = ChannelBuffers.wrappedBuffer(x)
    val header = channel.getConfig().getBufferFactory().getBuffer(b.order(), 4)
    header.writeInt(b.readableBytes())
    ChannelBuffers.wrappedBuffer(header, b)
  }


  override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
    val rpc = e.getMessage.asInstanceOf[Rpc4Tuple]
    val cf = Channels.future(e.getChannel)
    val buffer = ChannelBuffers.wrappedBuffer(
      (List(withLength(e.getChannel, rpc.header.toByteArray)) ++ rpc.payload.map(withLength(e.getChannel, _))):_*)
    cf.addListener(new JustReturnListener(e))
    Channels.write(ctx, cf, buffer)
  }
}

private class RpcRegisterer(registry: Registry) extends SimpleChannelUpstreamHandler {
  override def channelConnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    ctx.getPipeline.addLast("endpoint", registry.register(e.getChannel))
    super.channelConnected(ctx, e)
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    registry.unregister(e.getChannel)
    super.channelDisconnected(ctx, e)
  }
}

class RpcClient(val channel: Channel) extends SimpleChannelUpstreamHandler {
  private[this] val requests = new HashMap[Int, Promise[Option[Array[Byte]]]]
  private[this] val sequenceNumber = new AtomicInteger

  private class WriteHandler(requestId: Int) extends ChannelFutureListener {
    def operationComplete(p1: ChannelFuture) {
      if (!p1.isSuccess) {
        // Any write exception is a channel disconnect
        requests.synchronized { requests.remove(requestId) }.map(
          _.setException(new ChannelDisconnectedException(p1.getCause))
        )
      }
    }
  }

  private[this] def callUntyped(methodName: String, payload: Option[Array[Byte]]) = {
    val result = new Promise[Option[Array[Byte]]]
    val requestId = sequenceNumber.getAndIncrement
    val header = RpcFour.Header.newBuilder().setMessageType(RpcFour.Header.MessageType.REQUEST)
      .setMethod(methodName).setPayloadPresent(payload.isDefined).setSequence(requestId).build()
    requests.synchronized { requests.put(requestId, result) }
    try {
      Channels.write(channel, new Rpc4Tuple(header, payload)).addListener(new WriteHandler(requestId))
    } catch {
      // Any write exception is a channel disconnect
      case e: Throwable => throw new ChannelDisconnectedException(e)
    }

    result
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val rpc = e.getMessage.asInstanceOf[Rpc4Tuple]
    rpc.header.getMessageType match  {
      case RpcFour.Header.MessageType.RESPONSE =>
        requests.remove(rpc.header.getSequence.toInt).map(_.setValue(rpc.payload))
      case RpcFour.Header.MessageType.ERROR =>
        requests.remove(rpc.header.getSequence.toInt).map(
          _.setException(new RemoteError(rpc.payload.map(new String(_, "UTF-8")).getOrElse("Unspecified error"))))
      case _ =>
        super.messageReceived(ctx, e)
    }
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    requests.synchronized {
      requests.values.foreach(_.setException(new ChannelDisconnectedException))
      requests.clear()
    }
    super.channelDisconnected(ctx, e)
  }

  private[this] def callTrace[A](methodName: String, payload: Option[MessageLite])(f: Option[Array[Byte]] => A) =
    callUntyped(methodName, payload.map(_.toByteArray)).map(f(_))

  def call[I <: com.google.protobuf.Message](methodName: String, payload: MessageLite)(implicit manifest: Manifest[I]) =
    callTrace(methodName, Some(payload))(v => ProtobufTools.createProtobuf[I](v))

  def call[I <: com.google.protobuf.Message](methodName: String)(implicit manifest: Manifest[I]) =
    callTrace(methodName, None)(v => ProtobufTools.createProtobuf[I](v))

  def callNoResult(methodName: String, payload: MessageLite) =
    callTrace(methodName, Some(payload))(_ => ())

  def callNoResult(methodName: String) =
    callTrace(methodName, None)(_ => ())
}