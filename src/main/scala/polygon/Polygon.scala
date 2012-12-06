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
import org.stingray.contester.common.{ProblemDb, ProblemManifest}
import org.streum.configrity.Configuration
import scala.Some
import xml.{XML, Elem}
import org.stingray.contester.problems.{G4Test, ProblemTuple, ProblemURL}

class PolygonClientHttpException(reason: String) extends Throwable(reason)

object PolygonClient extends Logging {
  val connCache = new HashMap[InetSocketAddress, Service[HttpRequest, HttpResponse]]()

  def encodeFormData(data: Map[String, String]) =
    (for ((k, v) <- data) yield URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") ).mkString("&")

  def handleHttpResponse(response: HttpResponse): HttpResponse =
    if (response.getStatus == HttpResponseStatus.OK)
      response
    else
      throw new PolygonClientHttpException(response.getStatus.getReasonPhrase)


  def get(url: URL, formData: Map[String, String]) = {
    val postData = encodeFormData(formData)
    val addr = new InetSocketAddress(url.getHost, if (url.getPort == -1) url.getDefaultPort else url.getPort)
    val clientService = connCache.synchronized {
      connCache.getOrElseUpdate(addr, ClientBuilder()
        .codec(Http().maxResponseSize(new StorageUnit(64*1024*1024)))
        .hosts(addr)
        .hostConnectionLimit(1)
        .tcpConnectTimeout(Duration(5, TimeUnit.SECONDS))
        .build())
    }

    trace("Sending request for url %s".format(url.toString))

    val request = RequestBuilder()
      .url(url)
      .setHeader("Content-Type", MediaType.WwwForm)
      .setHeader("Content-Length", postData.length().toString)
      .buildPost(wrappedBuffer(postData.getBytes(Charsets.UTF_8)))

    clientService(request).map(handleHttpResponse(_))
  }

  def getPage(url: URL, formData: Map[String, String]) =
    get(url, formData).map(_.getContent.toString(Charsets.UTF_8))

  def getFile(url: URL, formData: Map[String, String]) =
    get(url, formData).map(_.getContent)

  def asXml(x: String) =
    XML.loadString(x)

  def apply(url: URL, authLogin: String, authPassword: String): SpecializedClient =
    new SpecializedClient(url, authLogin, authPassword)

  def apply(url: String, authLogin: String, authPassword: String): SpecializedClient =
    apply(new URL(url), authLogin, authPassword)

  def apply(conf: Configuration): SpecializedClient =
    apply(conf[String]("url", ""), conf[String]("login"), conf[String]("password"))
}

// 1. auth/post logic
// 2. url construction

class PolygonClient(authLogin: String, authPassword: String) extends Logging {
  val postMap = Map("login" -> authLogin, "password" -> authPassword)

  def get(url: URL, extraPostMap: Map[String, String] = Map()) =
    PolygonClient.get(url, postMap ++ extraPostMap)

  def getFile(url: URL, extraPostMap: Map[String, String] = Map()) =
    PolygonClient.getFile(url, postMap ++ extraPostMap)

  def getPage(url: URL, extraPostMap: Map[String, String] = Map()) =
    PolygonClient.getPage(url, postMap ++ extraPostMap)

  def getXml(url: URL) =
    getPage(url).map(PolygonClient.asXml(_))

  def getContest(url: URL): Future[Contest] =
    getXml(new URL(url, "contest.xml")).map(Contest(_))

  def getProblem(url: URL) =
    getXml(new URL(url, "problem.xml")).map(Problem(_, url))

  def getProblemFile(problem: ProblemTuple) =
    getFile(problem.url.defaultUrl, Map("revision" -> problem.revision.toString)).map{ d=>
      val bufferBytes = new Array[Byte](d.readableBytes())
      d.getBytes(d.readerIndex(), bufferBytes)
      trace("Download of " + problem.url.defaultUrl + " finished.")
      bufferBytes
    }
}

class SpecializedClient(url: URL, authLogin: String, authPassword: String) extends PolygonClient(authLogin, authPassword) {
  def getContest(contestId: Int): Future[Contest] =
    getContest(new URL(url, "c/%d/".format(contestId)))

  def getProblem(problemId: ProblemURL): Future[Problem] =
    getProblem(problemId.url(url))
}

object Contest {
  def apply(x: Elem) =
    new Contest(x)
}

object Problem {
  def apply(x: Elem, url: URL) =
    new Problem(x, Some(ProblemURL(url)))

  def apply(x: Elem) =
    new Problem(x, None)

  def apply(x: Elem, url: ProblemURL) =
    new Problem(x, Some(url))
}

class Contest(val source: Elem) {
  lazy val names =
    (source \ "names" \ "name").map(entry => ((entry \ "@language").text.toLowerCase, (entry \ "@value").text)).toMap

  lazy val defaultName =
    names.getOrElse("english", names.getOrElse("russian", names.values.headOption.getOrElse("Unnamed contest")))

  def getName(language: String) =
    names.getOrElse(language, defaultName)

  lazy val problems =
    (source \ "problems" \ "problem").map(entry =>
      ((entry \ "@index").text.toUpperCase, (entry \ "@url").text)).toMap.mapValues(ProblemURL(_))

  override def toString = source.toString()
}

class ContestWithProblems(contest: Contest, val problems: Map[String, Problem]) {
  lazy val names = contest.names
  override def toString = contest.toString
  lazy val defaultName = contest.defaultName
  def getName(language: String) =
    contest.getName(language)
}

class Problem(val source: Elem, val externalUrl: Option[ProblemURL]) extends ProblemTuple {
  lazy val internalUrl =
    ProblemURL((source \ "@url").text)

  lazy val url = externalUrl.getOrElse(internalUrl)

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
    (source \ "judging" \ "testset" \ "memory-limit").text.toInt

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

  lazy val shortId =
    url.short

  lazy val handle = this.asInstanceOf[ProblemTuple]

  def sanitized(m: ProblemManifest, pdb: ProblemDb) =
    new SanitizedProblem(source, url, m, pdb)
}

class SanitizedProblem(source: Elem, url: ProblemURL, val manifest: ProblemManifest, val pdb: ProblemDb) extends Problem(source, Some(url)) {
  def getTest(testId: Int) =
    new G4Test(this, pdb, testId)
}