package org.stingray.contester.polygon

import java.io.InputStream
import java.net.URI
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import com.twitter.finagle.Service
import com.twitter.finagle.redis.Client
import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.io.{BufInputStream, Charsets}
import com.twitter.util.{Duration, Future, Promise}
import org.apache.http.client.utils.URIBuilder
import org.stingray.contester.problems.Problem
import org.stingray.contester.utils.{ScannerCache, SerialHash}

import scala.xml.XML

case class PolygonContest(uri: URI) extends AnyVal {
  def redisKey = s"polygonContest/$uri"
}

case class PolygonProblemShort(uri: URI) extends AnyVal {
  def redisKey = s"polygonProblem/$uri"
  def fullUri: URI = {
    new URIBuilder(uri.resolve("problem.xml")).build()
  }
}

case class PolygonProblemID(uri: URI, revision: Int) {

  def fileUri: URI = {
    new URIBuilder(uri).addParameter("revision", revision.toString).build()
  }

  def fullUri: URI = {
    new URIBuilder(uri.resolve("problem.xml")).addParameter("revision", revision.toString).build()
  }

  def redisKey = s"polygonProblem/${fullUri}"
}

case class ContestWithProblems(contest: ContestDescription, problems: Map[String, PolygonProblem])

trait PolygonContestClient {
  def getContest(contest: PolygonContest): Future[ContestWithProblems]
}

trait PolygonProblemClient {
  def getProblem(contest: PolygonContestId, problem: String): Future[Option[Problem]]
}

case class PolygonProblemNotFoundException(problem: PolygonProblemShort) extends Throwable
case class PolygonContestNotFoundException(contest: PolygonContest) extends Throwable


case class ContestClient1(service: Service[URI, Option[PolygonResponse]], store: Client)
  extends ScannerCache[PolygonContest, ContestDescription, String]{
  def parse(content: String) =
    ContestDescription.parse(XML.loadString(content))

  def nearGet(contest: PolygonContest): Future[Option[String]] =
    store.get(StringToChannelBuffer(contest.redisKey)).map(_.map(_.toString(Charsets.Utf8)))


  override def nearPut(key: PolygonContest, value: String): Future[Unit] =
    store.set(StringToChannelBuffer(key.redisKey), StringToChannelBuffer(value))

  def farGet(contest: PolygonContest): Future[String] =
    service(contest.uri).map {
      case Some(x) => x.response.contentString
      case None => throw PolygonContestNotFoundException(contest)
    }
}

case class ProblemClient1(service: Service[URI, Option[PolygonResponse]], store: Client)
  extends ScannerCache[PolygonProblemShort, PolygonProblem, String] {
  def parse(content: String) =
    PolygonProblem.parse(XML.loadString(content))

  override def nearGet(key: PolygonProblemShort): Future[Option[String]] =
    store.get(StringToChannelBuffer(key.redisKey)).map(_.map(_.toString(Charsets.Utf8)))

  override def farGet(key: PolygonProblemShort): Future[String] =
    service(key.fullUri).map {
      case Some(x) => x.response.contentString
      case None => throw PolygonProblemNotFoundException(key)
    }

  override def nearPut(key: PolygonProblemShort, value: String): Future[Unit] =
    store.set(StringToChannelBuffer(key.redisKey), StringToChannelBuffer(value))
}

case class PolygonClient(service: Service[URI, Option[PolygonResponse]], store: Client)
  extends PolygonContestClient with PolygonProblemClient {

  private[this] val contestClient = ContestClient1(service, store)
  private[this] val problemClient = ProblemClient1(service, store)

  override def getContest(contest: PolygonContest): Future[ContestWithProblems] =
    contestClient.refresh(contest).flatMap { cdesc =>
      Future.collect(cdesc.problems.mapValues(PolygonProblemShort).mapValues(problemClient.refresh)).map { pmap =>
        ContestWithProblems(cdesc, pmap)
      }
    }

  override def getProblem(contest: PolygonContestId, problem: String): Future[Option[Problem]] = ???

  def getProblemFile(problem: PolygonProblemID): Future[Option[InputStream]] =
    service(problem.fileUri).map { respOpt =>
      respOpt.map { resp =>
        new BufInputStream(resp.response.content)
      }
    }
}