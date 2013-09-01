package org.stingray.contester.polygon

import java.net.URL
import org.apache.http.client.utils.URLEncodedUtils
import org.apache.http.message.BasicNameValuePair
import org.apache.http.NameValuePair
import org.apache.commons.io.Charsets
import org.stingray.contester.problems.ProblemHandle

object PolygonURL {
  def getParams(url: URL) = {
    import collection.JavaConversions._
    URLEncodedUtils.parse(url.toURI, "UTF-8").map(x => x.getName -> x.getValue).toMap
  }

  def stripQuery(url: URL) =
    new URL(url.getProtocol, url.getHost, url.getPort, url.getPath)

  def withQuery(url: URL, params: Map[String, String]) =
    if (params.isEmpty)
      url
    else {
      import collection.JavaConversions._
      val newParams = asJavaIterable((getParams(url) ++ params).map(x => new BasicNameValuePair(x._1, x._2).asInstanceOf[NameValuePair]))
      val newQuery = URLEncodedUtils.format(newParams, Charsets.UTF_8)
      new URL(stripQuery(url), "?" + newQuery)
    }

  def apply(url: URL) = {
    val params = getParams(url)
    val revision = params.get("revision").map(_.toInt)

    new PolygonProblemHandle(stripQuery(url), revision)
  }

  def shortId(url: URL) =
    url.getPath.split("/").takeRight(2).mkString("/")
}

class PolygonProblemFile(problem: PolygonProblemHandle) extends PolygonClientRequest {
  def objectUrl: URL = problem.url

  def params: Iterable[(String, String)] = problem.params
}

class PolygonProblemHandle(val url: URL, val revision: Option[Int]) extends ProblemHandle with PolygonClientRequest with PolygonContestKey {
  val objectUrl = new URL(url, "problem.xml")
  val params = revision.map("revision" -> _.toString).toIterable

  def file = new PolygonProblemFile(this)

  override def equals(obj: Any): Boolean =
    obj match {
      case other: PolygonProblemHandle =>
        url.equals(other.url) && revision == other.revision
      case _ => super.equals(obj)
    }

  def toProblemURI: String = (Seq("polygon+", url.toString) ++ revision.map("?revision=%d".format(_)).toSeq).mkString
}
