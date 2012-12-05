package org.stingray.contester.common

import com.google.protobuf.ByteString
import com.twitter.util.Future
import grizzled.slf4j.Logging
import java.io.File
import java.security.MessageDigest
import java.util.zip.{Deflater, Inflater}
import org.apache.commons.io.{FilenameUtils, FileUtils}
import org.stingray.contester.proto.Blobs.{Module, Blob}

class BlobChecksumMismatch(oldChecksum: String, newChecksum: String) extends Throwable("%s vs. %s".format(oldChecksum, newChecksum))

object Blobs {
  private def bytesToString(x: Array[Byte]) = x.map("%02X" format _).mkString

  def getBinary(x: Blob): Array[Byte] = {
    val result = if (x.hasCompression) {
      if (x.getCompression.getMethod == Blob.CompressionInfo.CompressionType.METHOD_ZLIB) {
        val decompressor = new Inflater()
        decompressor.setInput(x.getData.toByteArray)
        val result = new Array[Byte](x.getCompression.getOriginalSize)
        val size = decompressor.inflate(result)
        if (size == x.getCompression.getOriginalSize)
          result
        else
          new Array[Byte](0)
      } else new Array[Byte](0)
    } else x.getData.toByteArray
    if (x.hasSha1) {
      val newSha1 = getSha1(result)
      if (!x.getSha1.toByteArray.sameElements(newSha1)) {
        throw new BlobChecksumMismatch(bytesToString(x.getSha1.toByteArray), bytesToString(newSha1))
      }
    }
    result
  }

  private[this] def compress(x: Array[Byte]) = {
    val compressor = new Deflater()
    compressor.setInput(x)
    val compressed = new Array[Byte](x.length)
    compressor.deflate(compressed)
    compressed
  }

  def getSha1(x: Array[Byte]) =
    MessageDigest.getInstance("SHA-1").digest(x)

  def storeBinary(x: Array[Byte]): Blob = {
    val compressed = compress(x)
    val sha1 = getSha1(x)

    if (compressed.length < (x.length - 8)) {
      Blob.newBuilder().setData(ByteString.copyFrom(compressed))
        .setCompression(Blob.CompressionInfo.newBuilder().setOriginalSize(x.length)
        .setMethod(Blob.CompressionInfo.CompressionType.METHOD_ZLIB))
        .setSha1(ByteString.copyFrom(sha1)).build()
    } else
      Blob.newBuilder().setData(ByteString.copyFrom(x)).setSha1(ByteString.copyFrom(sha1)).build()
  }

  def fromFile(file: File) =
    Future(if (file.isFile) Some(storeBinary(FileUtils.readFileToByteArray(file))) else None)
}

final class ModuleOps(val repr: Module) extends Logging {
  def getBinary =
    Blobs.getBinary(repr.getData)

  def setName(name: String) =
    repr.toBuilder.setName(name).build()

  def clearName =
    repr.toBuilder.clearName().build()

  def setType(name: String) =
    repr.toBuilder.setType(name).build()

  def setType(name: Option[String]) =
    name.map(repr.toBuilder.setType(_).build()).getOrElse(repr)

  def getT =
    if (repr.getType.isEmpty)
      Modules.extractType(repr.getName)
    else
      repr.getType

  def setTypeFromName = {
    val t = Modules.extractType(repr.getName)
    if (t.nonEmpty) {
      setType(t)
    } else {
      repr
    }
  }
}

object Modules {
  def apply(moduleType: String, moduleData: Array[Byte], moduleName: Option[String] = None) = {
    val b = Module.newBuilder().setType(moduleType).setData(Blobs.storeBinary(moduleData))
    moduleName.foreach(b.setName(_))
    b.build()
  }

  def apply(blob: Blob): Module =
    Module.newBuilder().setData(blob).build()

  def extractType(name: String) = FilenameUtils.getExtension(name)
}

trait SubmitWithModule {
  def moduleType: String
  def source: Array[Byte]

  def moduleTypeNoDot =
    if (moduleType(0) == '.') moduleType.substring(1)
    else moduleType

  lazy val sourceModule = Modules(moduleTypeNoDot, source)
}
