package org.stingray.contester.polygon

import java.net.URI

import com.google.common.base.Charsets
import com.twitter.finagle.http.{MediaType, Request, RequestBuilder, Response}
import com.twitter.finagle.{Filter, Service}
import com.twitter.io.Buf
import com.twitter.util.Future
import com.typesafe.config.{ConfigObject, ConfigValueType}
import org.apache.http.client.utils.{URIBuilder, URLEncodedUtils}
import org.apache.http.message.BasicNameValuePair
import org.stingray.contester.engine.ProblemDescription
import org.stingray.contester.problems.ProblemHandleWithRevision
import org.stingray.contester.utils.RequestWithURI

import scala.xml.Elem

case class PolygonAuthInfo2(username: String, password: String) {
  import scala.collection.JavaConverters._

  private def toParams = Seq(
    new BasicNameValuePair("login", username),
    new BasicNameValuePair("password", password)
  ).asJava

  def toPostBody = URLEncodedUtils.format(toParams, Charsets.UTF_8)
}

object Polygons {
  def fromConfig(config: ConfigObject): Map[String, PolygonConfig] = {
    import scala.collection.JavaConverters._

    config.asScala.flatMap {
      case (k, v) =>
        v.valueType() match {
          case ConfigValueType.OBJECT =>
            val o = v.asInstanceOf[ConfigObject].toConfig
            Some(PolygonConfig(k, Seq(new URI(o.getString("url"))),
              PolygonAuthInfo2(o.getString("username"), o.getString("password"))))
          case _ => None
        }
    }
  }.map(x => x.shortName -> x).toMap
}

case class PolygonConfig(shortName: String, uri: Iterable[URI], authInfo: PolygonAuthInfo2) {
  def contest(id: Int): PolygonContest =
    PolygonContest(uri.head.resolve(s"c/${id}/contest.xml"))
}

case class AuthPolygonMatcher(config: Iterable[PolygonConfig]) {
  // private val polygonBaseRe = new Regex("^(.*/)(c/\\d+/?.*|p/[^/]+/[^/]/?.*)$")
  def apply(uri: URI): Option[PolygonConfig] = {
    config.find(_.uri.exists(_.relativize(uri) != uri))
  }
}

case class PolygonResponse(polygon: PolygonConfig, response: Response)

object PolygonFilter {
  def buildRequest(uri: URI, authInfo2: PolygonAuthInfo2): Request = {
    val postData = Buf.Utf8(authInfo2.toPostBody)
    RequestBuilder()
      .url(uri.toURL)
      .setHeader("Content-Type", MediaType.WwwForm)
      .setHeader("Content-Length", postData.length.toString)
      .buildPost(postData)
  }
}

case class PolygonFilter(matcher: URI => Option[PolygonConfig]) extends Filter[URI, Option[PolygonResponse], RequestWithURI, Response] {
  import PolygonFilter._

  override def apply(request: URI, service: Service[RequestWithURI, Response]): Future[Option[PolygonResponse]] =
    matcher(request) match {
      case None => Future.None
      case Some(polygon) =>
        service(RequestWithURI(buildRequest(request, polygon.authInfo), request)).map { resp =>
          Some(PolygonResponse(polygon, resp))
        }
    }
}

case class ContestDescription(names: Map[String, String], problems: Map[String, URI]) {
  def getName(language: String) =
    names.getOrElse(language, names.getOrElse("english", names.headOption.map(_._2).getOrElse("")))
}

object ContestDescription {
  private[this] def fixProblemURI(problemURI: URI, contestURI: URI): URI =
    new URIBuilder(problemURI).setHost(contestURI.getHost).setScheme(contestURI.getScheme).setPort(contestURI.getPort).build()

  def parse(source: Elem, contestURI: URI): ContestDescription = {
    val names = (source \ "names" \ "name").map(entry => ((entry \ "@language").text.toLowerCase, (entry \ "@value").text)).toMap
    val problems = (source \ "problems" \ "problem").map(entry =>
      ((entry \ "@index").text.toUpperCase, (entry \ "@url").text)).toMap.mapValues(x => fixProblemURI(new URI(x), contestURI))
    ContestDescription(names, problems)
  }
}

case class PolygonProblem(uri: URI, revision: Long, names: Map[String, String],
                          timeLimitMicros: Long, memoryLimit: Long, testCount: Int, tags: Set[String]) extends
  ProblemDescription with ProblemHandleWithRevision {
  override def handle: String = uri.toASCIIString

  override def interactive: Boolean = tags("interactive")

  override def stdio: Boolean = tags("stdio")

  def toId = PolygonProblemID(uri, revision)

  def defaultTitle =
    names.get("english").orElse(names.get("russian")).getOrElse("Unnamed problem")

  def getTitle(language: String) =
    names.getOrElse(language, defaultTitle)

}

object PolygonProblem {
  def parse(source: Elem): PolygonProblem = {
    val mainTestSet =
      (source \ "judging" \ "testset").filter(node => (node \ "@name").text == "tests")

    PolygonProblem(
      new URI((source \ "@url").text),
      (source \ "@revision").text.toInt,
      (source \ "names" \ "name").map(entry => ((entry \ "@language").text.toLowerCase, (entry \ "@value").text)).toMap,
      (mainTestSet \ "time-limit").text.toLong * 1000,
      (mainTestSet \ "memory-limit").text.toLong,
      (mainTestSet \ "test-count").text.toInt,
      (source \ "tags" \ "tag").map(entry => (entry \ "@value").text).toSet
    )
  }
}