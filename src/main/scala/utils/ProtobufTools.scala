package org.stingray.contester.utils

import com.google.protobuf.Message
import java.io.InputStream

object ProtobufTools {
  private[this] val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])
  private[this] val ARRAY_OF_INPUT_STREAM = Array[Class[_]](classOf[InputStream])

  def createProtobufFromBytes[I <: Message](bytes: Array[Byte])(implicit manifest: Manifest[I]): I = {
    manifest.runtimeClass.getDeclaredMethod("parseFrom", ARRAY_OF_BYTE_ARRAY: _*).invoke(null, bytes).asInstanceOf[I]
  }

  def createProtobufFromInputStream[I <: Message](input: InputStream)(implicit manifest: Manifest[I]): I = {
    manifest.runtimeClass.getDeclaredMethod("parseFrom", ARRAY_OF_INPUT_STREAM: _*).invoke(null, input).asInstanceOf[I]
  }

  def createEmptyProtobuf[I <: Message](implicit manifest: Manifest[I]): I = {
    manifest.runtimeClass.getDeclaredMethod("getDefaultInstance").invoke(null).asInstanceOf[I]
  }

  def createProtobuf[I <: Message](opt: Option[Array[Byte]])(implicit manifest: Manifest[I]): I =
    opt.map(createProtobufFromBytes[I](_)).getOrElse(createEmptyProtobuf[I])

}

