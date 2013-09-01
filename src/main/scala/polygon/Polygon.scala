package org.stingray.contester.polygon

import collection.mutable.HashMap
import com.google.common.base.Charsets
import com.twitter.finagle.{Filter, Service}
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.http.{MediaType, RequestBuilder, Http}
import com.twitter.util.{StorageUnit, Duration, Future}
import grizzled.slf4j.Logging
import java.net.{URLEncoder, InetSocketAddress, URL}
import java.util.concurrent.TimeUnit
import org.jboss.netty.buffer.ChannelBuffers.wrappedBuffer
import org.jboss.netty.handler.codec.http.{HttpResponseStatus, HttpResponse, HttpRequest}
import scala.Some
import xml.Elem
import org.stingray.contester.engine.ProblemDescription
import org.jboss.netty.buffer.ChannelBuffer
import com.google.common.cache.{CacheBuilder, CacheLoader}
import scala.util.matching.Regex

class PolygonClientHttpException(reason: String) extends Throwable(reason)
class PolygonAuthException(url: URL) extends Throwable(url.toString)

class PolygonAuthInfo(val username: String, val password: String) {
  def asMap = Map(
    "username" -> username,
    "password" -> password
  )
}

class PolygonBase(val shortName: String, val url: URL, username: String, password: String) {
  val authInfo: PolygonAuthInfo = new PolygonAuthInfo(username, password)
}

trait PolygonClientRequest {
  def objectUrl: URL
  def params: Iterable[(String, String)]

  override def equals(obj: scala.Any): Boolean =
    obj match {
      case other: PolygonClientRequest =>
        objectUrl.equals(other.objectUrl) && params.sameElements(other.params)
      case _ =>
        super.equals(obj)
    }
}

class PolygonAuthenticatedRequest(val url: URL, sourceParams: Iterable[(String, String)], authInfo: PolygonAuthInfo) {
  def params = sourceParams ++ authInfo.asMap
}

class ContestHandle(val url: URL) extends PolygonClientRequest with PolygonContestKey {
  val objectUrl = new URL(url, "contest.xml")
  val params = Nil

  override def equals(obj: scala.Any): Boolean =
    obj match {
      case other: ContestHandle =>
        objectUrl.equals(other)
      case _ => super.equals(obj)
    }
}



object PolygonClient extends Logging {
  def asPage(x: ChannelBuffer) =
    x.toString(Charsets.UTF_8)

  def asFile(x: HttpResponse) =
    x.getContent

  def asByteArray(buffer: ChannelBuffer) = {
    val bufferBytes = new Array[Byte](buffer.readableBytes())
    buffer.getBytes(buffer.readerIndex(), bufferBytes)
    bufferBytes
  }
}

object CachedConnectionHttpService extends Service[HttpRequest, HttpResponse] {
  private object PolygonClientCacheLoader extends CacheLoader[InetSocketAddress, Service[HttpRequest, HttpResponse]] {
    def load(key: InetSocketAddress): Service[HttpRequest, HttpResponse] = ClientBuilder()
      .codec(Http().maxResponseSize(new StorageUnit(64*1024*1024)))
      .hosts(key)
      .hostConnectionLimit(1)
      .tcpConnectTimeout(Duration(5, TimeUnit.SECONDS))
      .build()
  }

  private val connCache = CacheBuilder.newBuilder()
    .expireAfterAccess(30, TimeUnit.MINUTES)
    .build(PolygonClientCacheLoader)

  def apply(request: HttpRequest): Future[HttpResponse] = {
    val url = new URL(request.getUri)
    val addr = new InetSocketAddress(url.getHost, if (url.getPort == -1) url.getDefaultPort else url.getPort)
    connCache.get(addr)(request)
  }
}

object BasicPolygonFilter extends Filter[PolygonAuthenticatedRequest, ChannelBuffer, HttpRequest, HttpResponse] {
  private def encodeFormData(data: Iterable[(String, String)]) =
    (for ((k, v) <- data) yield URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") ).mkString("&")

  private def handleHttpResponse(response: HttpResponse): ChannelBuffer =
    if (response.getStatus == HttpResponseStatus.OK)
      response.getContent
    else
      throw new PolygonClientHttpException(response.getStatus.getReasonPhrase)

  def apply(request: PolygonAuthenticatedRequest, service: Service[HttpRequest, HttpResponse]): Future[ChannelBuffer] = {
    val postData = encodeFormData(request.params)
    val httpRequest = RequestBuilder()
      .url(request.url)
      .setHeader("Content-Type", MediaType.WwwForm)
      .setHeader("Content-Length", postData.length().toString)
      .buildPost(wrappedBuffer(postData.getBytes(Charsets.UTF_8)))

    service(httpRequest).map(handleHttpResponse(_))
  }
}

class AuthPolygonFilter extends Filter[PolygonClientRequest, ChannelBuffer, PolygonAuthenticatedRequest, ChannelBuffer] {
  private val polygonBaseRe = new Regex("^(.*/)(c/\\d+/?.*|p/[^/]+/[^/]/?.*)$")
  val bases = new HashMap[String, PolygonBase]()

  def addPolygon(base: PolygonBase) =
    bases.put(base.url.toString, base)

  def extractPolygonBase(url: URL) =
    polygonBaseRe.findFirstMatchIn(url.getPath).map(_.group(1)).map(new URL(url.getProtocol, url.getHost, url.getPort, _))

  def apply(request: PolygonClientRequest, service: Service[PolygonAuthenticatedRequest, ChannelBuffer]): Future[ChannelBuffer] = {
    val baseOpt = extractPolygonBase(request.objectUrl).flatMap(x => bases.get(x.toString))
    if (baseOpt.isDefined)
      service(new PolygonAuthenticatedRequest(request.objectUrl, request.params, baseOpt.get.authInfo))
    else
      Future.exception(new PolygonAuthException(request.objectUrl))
  }
}

class ContestDescription(val source: Elem) {
  lazy val names =
    (source \ "names" \ "name").map(entry => ((entry \ "@language").text.toLowerCase, (entry \ "@value").text)).toMap

  lazy val defaultName =
    names.getOrElse("english", names.getOrElse("russian", names.values.headOption.getOrElse("Unnamed contest")))

  def getName(language: String) =
    names.getOrElse(language, defaultName)

  lazy val problems =
    (source \ "problems" \ "problem").map(entry =>
      ((entry \ "@index").text.toUpperCase, (entry \ "@url").text)).toMap
      .mapValues { str =>
      new PolygonProblemHandle(new URL(str), None)
    }

  override def equals(obj: scala.Any): Boolean =
    obj match {
      case other: ContestDescription =>
        source.equals(other.source)
      case _ => super.equals(obj)
    }

  override def toString = source.toString()
}

class ContestWithProblems(val contest: ContestDescription, val problems: Map[String, PolygonProblem]) {
  lazy val names = contest.names
  override def toString = contest.toString
  lazy val defaultName = contest.defaultName
  def getName(language: String) =
    contest.getName(language)

  override def equals(obj: scala.Any): Boolean =
    obj match {
      case other: ContestWithProblems =>
        contest.equals(other.contest) && problems.sameElements(other.problems)
      case _ => super.equals(obj)
    }
}

class PolygonProblem(val source: Elem, val externalUrl: Option[URL]) extends ProblemDescription {
  override def toString = "PolygonProblem(%s, %d)".format(url, revision)
  val pdbId: String = (url.getProtocol :: url.getHost :: url.getPath :: Nil).mkString("/")
  val id = pdbId

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

  lazy val testCount =
    (source \ "judging" \ "testset" \ "test-count").text.toInt

  lazy val timeLimit =
    (source \ "judging" \ "testset" \ "time-limit").text.toInt

  lazy val memoryLimit =
    (source \ "judging" \ "testset" \ "memory-limit").text.toLong

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