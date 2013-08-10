package org.stingray.contester.common

import com.google.protobuf.ByteString
import com.twitter.util.Future
import java.io.File
import java.security.MessageDigest
import java.util.zip.{Deflater, Inflater}
import org.apache.commons.io.FileUtils
import org.stingray.contester.proto.Blobs.Blob

class BlobChecksumMismatch(oldChecksum: String, newChecksum: String) extends Throwable("%s vs. %s".format(oldChecksum, newChecksum))

object Blobs {
  def bytesToString(x: Array[Byte]) = x.map("%02X" format _).mkString

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

trait SubmitWithModule {
  def sourceModule: Module
}
