package org.stingray.contester.problems

import java.net.URI

object ProblemURI {
  def getStoragePrefix(url: URI): String = {
    val schemePrefix = url.getScheme match {
      case "direct" => url.getScheme
      case "http" | "https" => s"polygon/${url.getScheme}"
    }

    val portPart = if (url.getPort != -1) s"/${url.getPort.toString}" else ""
    val pathPart = url.getPath.stripPrefix("/").stripSuffix("/")

    s"$schemePrefix/${url.getHost}$portPart/$pathPart"
  }

  def getStoragePrefix(handle: URI, revision: Long): String =
    s"${getStoragePrefix(handle)}/$revision"

  def getStoragePrefix(handle: String, revision: Long): String =
    getStoragePrefix(new URI(handle), revision)

  def getStoragePrefix(p: ProblemHandleWithRevision): String =
    getStoragePrefix(p.handle, p.revision)
}