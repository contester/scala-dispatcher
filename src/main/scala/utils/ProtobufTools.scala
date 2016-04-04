package org.stingray.contester.utils

import com.google.protobuf.Message
import java.io.InputStream

import com.trueaccord.scalapb.GeneratedMessage
import com.twitter.io.Buf
import org.jboss.netty.buffer.ChannelBuffer

import scala.reflect.macros.blackbox

/** Parametrized functions to create protobufs.
  * You will usually do createProtobuf[MessageType](value).
  *
  */
object ProtobufTools {
  private[this] val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])
  private[this] val ARRAY_OF_INPUT_STREAM = Array[Class[_]](classOf[InputStream])

  def asByteArray(buffer: ChannelBuffer) = {
    val bufferBytes = new Array[Byte](buffer.readableBytes())
    buffer.getBytes(buffer.readerIndex(), bufferBytes)
    bufferBytes
  }

  def createProtobuf[I <: Message](buffer: ChannelBuffer)(implicit manifest: Manifest[I]): I =
    createProtobuf(asByteArray(buffer))


  def createProtobuf[I <: Message](buffer: Buf)(implicit manifest: Manifest[I]): I =
    createProtobuf(Buf.ByteArray.Shared.extract(buffer))

  def createProtobuf[I <: Message](bytes: Array[Byte])(implicit manifest: Manifest[I]): I = {
    manifest.runtimeClass.getDeclaredMethod("parseFrom", ARRAY_OF_BYTE_ARRAY: _*).invoke(null, bytes).asInstanceOf[I]
  }

  /** Create protobuf from InputStream.
    *
    * @param input Stream to parse.
    * @param manifest Manifest for I
    * @tparam I Protobuf message.
    * @return Parsed proto.
    */
  def createProtobuf[I <: Message](input: InputStream)(implicit manifest: Manifest[I]): I = {
    manifest.runtimeClass.getDeclaredMethod("parseFrom", ARRAY_OF_INPUT_STREAM: _*).invoke(null, input).asInstanceOf[I]
  }

  private def createEmpty[I <: Message](implicit manifest: Manifest[I]): I = {
    manifest.runtimeClass.getDeclaredMethod("getDefaultInstance").invoke(null).asInstanceOf[I]
  }

  /** Create protobuf from Option[Array[Byte]].
    * Will return default proto if argument was None.
    *
    * @param opt Array[Byte] with proto, or not.
    * @param manifest
    * @tparam I
    * @return
    */
  def createProtobuf[I <: Message](opt: Option[Array[Byte]])(implicit manifest: Manifest[I]): I =
    opt.map(createProtobuf[I](_)).getOrElse(createEmpty[I])
}