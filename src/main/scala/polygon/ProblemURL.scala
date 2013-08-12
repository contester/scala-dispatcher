package org.stingray.contester.polygon

import java.net.URL
import org.stingray.contester.problems.ProblemHandle
import org.apache.http.client.utils.URLEncodedUtils
import org.apache.http.NameValuePair
import org.apache.http.message.BasicNameValuePair
import com.google.common.base.Charsets

class PolygonAuthInfo(val username: String, val password: String) {
  def asMap = Map(
    "username" -> username,
    "password" -> password
  )
}

trait PolygonURL {
  def url: URL
  def authInfo: Option[PolygonAuthInfo]

  def params: Map[String, String]

  def withUrl(newUrl: URL) =
    new SimplePolygonURI(newUrl, authInfo, params)
}

class PolygonBase(val shortName: String, val url: URL, username: String, password: String) extends PolygonURL {
  val authInfo: Option[PolygonAuthInfo] = Some(new PolygonAuthInfo(username, password))
  def params: Map[String, String] = Map.empty
}

object PolygonURL {
  private def getAuthUserInfo(url: URL) =
    Option(url.getUserInfo).flatMap { userInfo =>
      val splits = userInfo.split(':')
      if (splits.length == 2)
        Some(new PolygonAuthInfo(splits(0), splits(1)))
      else
        None
    }

  private def getParamUserInfo(params: Map[String, String]) =
    if (params.contains("username") && params.contains("password"))
      Some(new PolygonAuthInfo(params("username"), params("password")))
    else
      None

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
    val authInfo = getAuthUserInfo(url).orElse(getParamUserInfo(params))
    val revision = params.get("revision").map(_.toInt)

    new PolygonProblemHandle(stripQuery(url), revision, authInfo)
  }

  def shortId(url: URL) =
   url.getPath.split("/").takeRight(2).mkString("/")
}

class SimplePolygonURI(val url: URL, val authInfo: Option[PolygonAuthInfo], val params: Map[String, String]) extends PolygonURL

class PolygonProblemHandle(val url: URL, val revision: Option[Int], val authInfo: Option[PolygonAuthInfo]) extends ProblemHandle with PolygonURL {
  val params = revision.map("revision" -> _.toString)

  def toProblemURI: String = (Seq("polygon+", url.toString) ++ revision.map("?revision=%d".format(_)).toSeq).mkString
}