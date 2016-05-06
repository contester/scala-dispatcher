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
import org.stingray.contester.problems.ProblemWithRevision

import scala.xml.Elem

case class PolygonAuthInfo2(username: String, password: String) {
  import scala.collection.JavaConversions._

  private def toParams = Seq(
    new BasicNameValuePair("login", username),
    new BasicNameValuePair("password", password)
  )

  def toPostBody = URLEncodedUtils.format(toParams, Charsets.UTF_8)
}

object Polygons {
  def fromConfig(config: ConfigObject): Map[String, PolygonConfig] = {
    import scala.collection.JavaConversions._

    config.flatMap {
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

case class PolygonFilter(matcher: URI => Option[PolygonConfig]) extends Filter[URI, Option[PolygonResponse], Request, Response] {
  import PolygonFilter._

  override def apply(request: URI, service: Service[Request, Response]): Future[Option[PolygonResponse]] =
    matcher(request) match {
      case None => Future.None
      case Some(polygon) =>
        service(buildRequest(request, polygon.authInfo)).map { resp =>
          Some(PolygonResponse(polygon, resp))
        }
    }
}

/*
object PolygonClient extends Logging {
  def asFile(x: Response) =
    x.content

  def asByteArray(buffer: Buf) = {
    Buf.ByteArray.Owned.extract(Buf.ByteArray.coerce(buffer))
  }
}
*/

// parsed contest xml
case class ContestDescription(names: Map[String, String], problems: Map[String, URI])

object ContestDescription {
  def parse(source: Elem): ContestDescription = {
    val names = (source \ "names" \ "name").map(entry => ((entry \ "@language").text.toLowerCase, (entry \ "@value").text)).toMap
    val problems = (source \ "problems" \ "problem").map(entry =>
      ((entry \ "@index").text.toUpperCase, (entry \ "@url").text)).toMap.mapValues(x => new URI(x))
    ContestDescription(names, problems)
  }
}

private object PolygonProblemUtils {
  def getPathPart(url: URI) =
    url.getPath.stripPrefix("/").stripSuffix("/")

  def getPdbPath(url: URI): String =
    ("polygon" :: url.getScheme :: url.getHost :: (if (url.getPort != -1) url.getPort.toString :: getPathPart(url) :: Nil else getPathPart(url) :: Nil)).mkString("/")
}

case class PolygonProblem(uri: URI, revision: Long, names: Map[String, String],
                          timeLimitMicros: Long, memoryLimit: Long, testCount: Int, tags: Set[String]) extends ProblemDescription {
  /**
    * Defines problem ID in pdb/gridfs. Needs to be storage-compatible.
    *
    * @return Problem ID in pdb/gridfs.
    */
  override def pid: String = PolygonProblemUtils.getPdbPath(uri)

  override def interactive: Boolean = tags("interactive")

  override def stdio: Boolean = tags("stdio")

  def toId = PolygonProblemID(uri, revision)
}

object PolygonProblem {
  def parse(source: Elem): PolygonProblem = {
    val mainTestSet =
      (source \ "judging" \ "testset").filter(node => (node \ "@name").text == "tests")

    PolygonProblem(
      new URI((source \ "@url").text),
      (source \ "@revision").text.toInt,
      (source \ "names" \ "name").map(entry => ((entry \ "@language").text.toLowerCase, (entry \ "@value").text)).toMap,
      (mainTestSet \ "time-limit").text.toInt,
      (mainTestSet \ "memory-limit").text.toLong,
      (mainTestSet \ "test-count").text.toInt,
      (source \ "tags" \ "tag").map(entry => (entry \ "@value").text).toSet
    )
  }
}

/*
class PolygonProblem0(val source: Elem, val externalUrl: Option[URL]) extends ProblemDescription {
  override def toString = "PolygonProblem(%s, %d)".format(url, revision)

  // If I override it with val, it breaks override - shows up as null in some parts of ProblemID
  def pid = PolygonProblemUtils.getPdbPath(url)
  val handle = new PolygonProblemHandle(url, Some(revision))

  override def equals(obj: Any): Boolean =
    obj match {
      case other: PolygonProblem =>
        source.equals(other.source) && externalUrl == other.externalUrl
      case _ => super.equals(obj)
    }

  lazy val internalUrl =
    new URL((source \ "@url").text)

  lazy val url = externalUrl.getOrElse(internalUrl)

  def timeLimitMicros: Long = timeLimit * 1000

  lazy val titles =
    (source \ "statements" \ "statement").map(entry => ((entry \ "@language").text.toLowerCase, (entry \ "@title").text)).toMap

  lazy val names =
    (source \ "names" \ "name").map(entry => ((entry \ "@language").text.toLowerCase, (entry \ "@value").text)).toMap

  def getName(language: String): Option[String] =
    names.get(language).orElse(titles.get(language).flatMap(x => if (x.isEmpty) None else Some(x)))

  lazy val defaultTitle =
    getName("english").orElse(getName("russian")).getOrElse("Unnamed problem")

  def getTitle(language: String) =
    getName(language).getOrElse(defaultTitle)

  lazy val revision =
    (source \ "@revision").text.toInt

  lazy val mainTestSet =
    (source \ "judging" \ "testset").filter(node => (node \ "@name").text == "tests")

  lazy val testCount =
    (mainTestSet \ "test-count").text.toInt

  lazy val timeLimit =
    (mainTestSet \ "time-limit").text.toInt

  lazy val memoryLimit =
    (mainTestSet \ "memory-limit").text.toLong

  lazy val inputFile =
    (source \ "judging" \ "@input-file").text

  lazy val outputFile =
    (source \ "judging" \ "@output-file").text

  lazy val stdio =
    inputFile == "" && outputFile == ""

  lazy val tags =
    (source \ "tags" \ "tag").map(entry => (entry \ "@value").text).toSet

  lazy val interactive = tags.contains("interactive")
  lazy val semi = tags.contains("semi-interactive-16")
}
*/