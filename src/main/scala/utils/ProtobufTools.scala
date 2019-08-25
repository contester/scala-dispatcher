package org.stingray.contester.utils

import com.twitter.io.Buf
import scalapb.{GeneratedMessage, GeneratedMessageCompanion, Message}

/** Parametrized functions to create protobufs.
  * You will usually do createProtobuf[MessageType](value).
  *
  */
object ProtobufTools {
  def createProtobuf[I <: GeneratedMessage with Message[I]](buffer: Buf)(implicit cmp: GeneratedMessageCompanion[I]): I = {
    createProtobuf(Buf.ByteArray.Shared.extract(buffer))
  }

  def createProtobuf[I <: GeneratedMessage with Message[I]](bytes: Array[Byte])(implicit cmp: GeneratedMessageCompanion[I]): I = {
    cmp.parseFrom(bytes)
  }

  /** Create protobuf from Option[Array[Byte]].
    * Will return default proto if argument was None.
    *
    * @param opt Array[Byte] with proto, or not.
    * @tparam I
    * @return
    */
  def createProtobuf[I <: GeneratedMessage with Message[I]](opt: Option[Array[Byte]])(implicit cmp: GeneratedMessageCompanion[I]): I =
    opt.map(createProtobuf[I](_)).getOrElse(cmp.defaultInstance)
}