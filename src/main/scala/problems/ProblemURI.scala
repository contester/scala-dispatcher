package org.stingray.contester.problems

import java.net.URI

case class StoragePrefix(val self: String) extends AnyVal {
  def prefix = s"problem/$self"
  def dbName(suffix: String) = s"$prefix/$suffix"
  def archiveName = dbName("archive")

  override def toString: String = self.toString
}

object ProblemURI {
  def getStoragePrefix(url: URI): StoragePrefix = {
    val schemePrefix = url.getScheme match {
      case "direct" => url.getScheme
      case "http" | "https" => s"polygon/${url.getScheme}"
    }

    val portPart = if (url.getPort != -1) s"/${url.getPort.toString}" else ""
    val pathPart = url.getPath.stripPrefix("/").stripSuffix("/")

    StoragePrefix(s"$schemePrefix/${url.getHost}$portPart/$pathPart")
  }

  def getStoragePrefix(handle: URI, revision: Long): StoragePrefix =
    StoragePrefix(s"${getStoragePrefix(handle)}/$revision")

  def getStoragePrefix(handle: String, revision: Long): StoragePrefix =
    getStoragePrefix(new URI(handle), revision)

  def getStoragePrefix(p: ProblemHandleWithRevision): StoragePrefix =
    getStoragePrefix(p.handle, p.revision)
}