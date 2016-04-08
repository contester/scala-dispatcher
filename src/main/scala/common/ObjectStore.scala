package org.stingray.contester.common

import com.twitter.util.Future
import org.apache.commons.io.FilenameUtils
import org.stingray.contester.invokers.Sandbox

object ObjectStore {
  def getMetadataString(metadata: Map[String, Any], key: String): String =
    metadata.getOrElse(key, "") match {
      case s: String =>
        s
      case _ =>
        ""
    }
}

trait HasGridfsPath {
  def toGridfsPath: String
}

class InstanceSubmitHandle(val baseUrl: Option[String], val handle: String, val submitId: Int) extends HasGridfsPath {
  def toGridfsPath: String = s"${baseUrl.getOrElse("")}submit/${handle}/${submitId}"
}

class InstanceSubmitTestingHandle(val baseUrl: Option[String], val handle: String, val submitId: Int, val testingId: Int) extends HasGridfsPath {
  def toGridfsPath: String = s"${baseUrl.getOrElse("")}submit/${handle}/${submitId}/${testingId}"

  def submit = new InstanceSubmitHandle(baseUrl, handle, submitId)
}

class GridfsPath(override val toGridfsPath: String) extends HasGridfsPath {
  def this(parent: HasGridfsPath, name: String) =
    this("%s/%s".format(parent.toGridfsPath, name))
}

case class ObjectMetaData(originalSize: Long, sha1sum: Option[String], moduleType: Option[String], compressionType: Option[String])

trait Module {
  def moduleType: String
  def moduleHash: String

  def putToSandbox(sandbox: Sandbox, destinationName: String): Future[Unit]
}

class ObjectStoreModule(name: String, val moduleType: String, val moduleHash: String) extends Module {
  def putToSandbox(sandbox: Sandbox, destinationName: String): Future[Unit] =
    sandbox.putGridfs(name, destinationName).unit
}

class ByteBufferModule(moduleTypeRaw: String, content: Array[Byte]) extends Module {
  val moduleHash = "sha1:" + Blobs.bytesToString(Blobs.getSha1(content)).toLowerCase
  val moduleType = Module.noDot(moduleTypeRaw)

  def putToSandbox(sandbox: Sandbox, destinationName: String): Future[Unit] =
    sandbox.put(Blobs.storeBinary(content), destinationName).unit
}

object Module {
  def extractType(filename: String) =
    FilenameUtils.getExtension(filename)

  def noDot(x: String): String =
    if (x(0) == '.')
      x.substring(1)
    else
      x
}

