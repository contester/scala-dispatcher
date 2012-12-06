package org.stingray.contester.problems

import java.net.URL

trait ProblemURL {
  def short: String
  def url(base: URL): URL
  def defaultUrl: URL

  override def hashCode() = short.hashCode
  override def equals(obj: Any) = obj match {
    case other: ProblemURL => short.equals(other.short)
    case _ => super.equals(obj)
  }

  override def toString = short
}

private class FullProblemURL(fullUrl: String) extends ProblemURL {
  lazy val short = fullUrl.split("/").takeRight(2).mkString("/")
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

class NoDefaultURLAvailable(shortUrl: String) extends Throwable(shortUrl)

private class ShortProblemURL(shortUrl: String) extends ProblemURL {
  lazy val short = shortUrl
  def url(base: URL) = new URL(base, short + "/")
  def defaultUrl = throw new NoDefaultURLAvailable(short)
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
