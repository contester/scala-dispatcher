package org.stingray.contester.common

import java.security.MessageDigest
import java.util.zip.{Deflater, Inflater}

import com.google.protobuf.ByteString
import org.stingray.contester.proto.Blob

class BlobChecksumMismatch(oldChecksum: String, newChecksum: String) extends Throwable("%s vs. %s".format(oldChecksum, newChecksum))

object Blobs {
  def bytesToString(x: Array[Byte]) = x.map("%02X" format _).mkString

  private def zlibDecompress(x: ByteString, originalSize: Int): Array[Byte] = {
    val decompressor = new Inflater()
    decompressor.setInput(x.toByteArray)
    val result = new Array[Byte](originalSize)
    val size = decompressor.inflate(result)
    if (size == originalSize)
      result
    else
      new Array[Byte](0)
  }

  def getBinary(x: Blob): Array[Byte] = {
    val result = x.compression.map { ci =>
      ci.method match {
        case Blob.CompressionInfo.CompressionType.METHOD_ZLIB =>
          zlibDecompress(x.data, ci.originalSize)
        case Blob.CompressionInfo.CompressionType.METHOD_NONE =>
          x.data.toByteArray
      }
    }.getOrElse(x.data.toByteArray)

    if (!x.sha1.isEmpty) {
      val newSha1 = getSha1(result)
      if (!x.sha1.toByteArray.sameElements(newSha1)) {
        throw new BlobChecksumMismatch(bytesToString(x.sha1.toByteArray), bytesToString(newSha1))
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
      Blob(data = ByteString.copyFrom(compressed),
        compression = Some(Blob.CompressionInfo(originalSize = x.length,
          method = Blob.CompressionInfo.CompressionType.METHOD_ZLIB)),
        sha1 = ByteString.copyFrom(sha1))
    } else
      Blob(data = ByteString.copyFrom(x), sha1 = ByteString.copyFrom(sha1))
  }
}

trait SubmitWithModule {
  def sourceModule: Module
}
