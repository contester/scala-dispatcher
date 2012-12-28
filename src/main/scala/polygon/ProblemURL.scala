package org.stingray.contester.polygon

import java.net.URL

trait ProblemURL {
  def shortId: String

  override def hashCode() = shortId.hashCode
  override def equals(obj: Any) = obj match {
    case other: ProblemURL => shortId.equals(other.shortId)
    case _ => super.equals(obj)
  }

  def url(base: URL): URL
  def defaultUrl: URL

  override def toString = "ProblemURL(%s)".format(shortId)
}

private class FullProblemURL(fullUrl: String) extends ProblemURL {
  lazy val shortId = fullUrl.split("/").takeRight(2).mkString("/")
  lazy val defaultUrl = addSlash(fullUrl)
  def url(base: URL) = defaultUrl

  private def addSlash(url: String) =
    new URL(
      if (url.endsWith("/"))
        url
      else
        url + "/"
    )
}

class NoDefaultURLAvailable(shortId: String) extends Throwable(shortId)

private class ShortProblemURL(val shortId: String) extends ProblemURL {
  def url(base: URL) = new URL(base, shortId + "/")
  def defaultUrl = throw new NoDefaultURLAvailable(shortId)
}

object ProblemURL {
  def apply(url: String): ProblemURL =
    if (url.startsWith("http"))
      new FullProblemURL(url)
    else
      new ShortProblemURL(url)

  def apply(url: URL): ProblemURL =
    new FullProblemURL(url.toString)
}

