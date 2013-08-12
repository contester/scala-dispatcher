package org.stingray.contester.polygon

import collection.mutable.HashMap
import com.google.common.base.Charsets
import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.http.{MediaType, RequestBuilder, Http}
import com.twitter.util.{StorageUnit, Duration, Future}
import grizzled.slf4j.Logging
import java.net.{URLEncoder, InetSocketAddress, URL}
import java.util.concurrent.TimeUnit
import org.jboss.netty.buffer.ChannelBuffers.wrappedBuffer
import org.jboss.netty.handler.codec.http.{HttpResponseStatus, HttpResponse, HttpRequest}
import scala.Some
import xml.{XML, Elem}
import org.stingray.contester.engine.ProblemDescription
import com.google.common.cache.{CacheLoader, CacheBuilder}
import scala.util.matching.Regex

class PolygonClientHttpException(reason: String) extends Throwable(reason)

// class PolygonClientRequest(val uri: URI, val formData: Map[String, String])

object PolygonClient extends Logging {
  private val polygonBaseRe = new Regex("^(.*/)(c/\\d+/?.*|p/[^/]+/[^/]/?.*)$")

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

  val bases = new HashMap[String, PolygonBase]()

  def addPolygon(base: PolygonBase) =
    bases.put(base.url.toString, base)

  def extractPolygonBase(url: URL) =
    polygonBaseRe.findFirstMatchIn(url.getPath).map(_.group(1)).map(new URL(url.getProtocol, url.getHost, url.getPort, _))

  private def encodeFormData(data: Iterable[(String, String)]) =
    (for ((k, v) <- data) yield URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") ).mkString("&")

  private def handleHttpResponse(response: HttpResponse): HttpResponse =
    if (response.getStatus == HttpResponseStatus.OK)
      response
    else
      throw new PolygonClientHttpException(response.getStatus.getReasonPhrase)

  private def get(url: URL, formData: Iterable[(String, String)]) = {
    val postData = encodeFormData(formData)
    val addr = new InetSocketAddress(url.getHost, if (url.getPort == -1) url.getDefaultPort else url.getPort)
    val clientService = connCache(addr)

    trace("Sending request for url %s".format(url.toString))

    val request = RequestBuilder()
      .url(url)
      .setHeader("Content-Type", MediaType.WwwForm)
      .setHeader("Content-Length", postData.length().toString)
      .buildPost(wrappedBuffer(postData.getBytes(Charsets.UTF_8)))

    clientService(request).map(handleHttpResponse(_))
  }

  def getOnly(url: URL, authInfo: Option[PolygonAuthInfo], params: Iterable[(String, String)]) =
    get(url, params ++ authInfo.flatMap(_.asMap))

  def get(url: URL, authInfo: Option[PolygonAuthInfo], params: Map[String, String]): Future[(HttpResponse, Option[PolygonBase])] = {
    val baseOpt = extractPolygonBase(url).flatMap(x => bases.get(x.toString))
    getOnly(url, authInfo.orElse(baseOpt.flatMap(_.authInfo)), params).map(_ -> baseOpt)
  }

  def get(url: PolygonURL) =
    get(url.url, url.authInfo, url.params)

  def asPage(x: HttpResponse) =
    x.getContent.toString(Charsets.UTF_8)

  def asFile(x: HttpResponse) =
    x.getContent

  def asXml(x: String) =
    XML.loadString(x)

  def getXml(url: PolygonURL) =
    get(url).map(x => asXml(asPage(x._1)) -> x._2)

  def getProblem(url: PolygonProblemHandle) =
    getXml(url.withUrl(new URL(url.url, "problem.xml"))).map(x => PolygonProblem(x._1, url))

  def getContest(url: PolygonURL) =
    getXml(url.withUrl(new URL(url.url, "contest.xml"))).map(x => new ContestDescription(x._1))

  def getProblemFile(url: PolygonProblemHandle) =
    get(url).map(x => asFile(x._1)).map { buffer =>
      val bufferBytes = new Array[Byte](buffer.readableBytes())
      buffer.getBytes(buffer.readerIndex(), bufferBytes)
      trace("Download of " + url + " finished.")
      bufferBytes
    }

//  def apply(conf: Configuration): SpecializedClient =
//    apply(conf[String]("url", ""), conf[String]("login"), conf[String]("password"))
}

object PolygonProblem {
  def apply(x: Elem, url: URL) =
    new PolygonProblem(x, Some(ProblemURL(url)))

  def apply(x: Elem) =
    new PolygonProblem(x, None)

  def apply(x: Elem, url: ProblemURL) =
    new PolygonProblem(x, Some(url))
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
      new PolygonProblemHandle(new URL(str), None, None)
    }

  override def toString = source.toString()
}

class ContestWithProblems(contest: ContestDescription, val problems: Map[String, PolygonProblem]) {
  lazy val names = contest.names
  override def toString = contest.toString
  lazy val defaultName = contest.defaultName
  def getName(language: String) =
    contest.getName(language)
}

class PolygonProblem(val source: Elem, val externalUrl: Option[URL]) extends ProblemDescription {
  override def toString = "PolygonProblem(%s, %d)".format(url, revision)


  val pdbId: String = (url.getProtocol :: url.getHost :: url.getPath :: Nil).mkString("/")

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
