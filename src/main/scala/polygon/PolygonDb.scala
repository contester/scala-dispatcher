package org.stingray.contester.polygon

import java.io.InputStream
import java.net.URI
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import com.twitter.finagle.Service
import com.twitter.finagle.redis.Client
import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.io.{Buf, BufInputStream, Charsets}
import com.twitter.util.{Duration, Future, Promise}
import grizzled.slf4j.Logging
import org.apache.http.client.utils.URIBuilder
import org.stingray.contester.engine.InvokerSimpleApi
import org.stingray.contester.problems.{Problem, SanitizeDb, SimpleProblemDb}
import org.stingray.contester.utils.{Fu, ScannerCache, SerialHash}

import scala.xml.XML

case class PolygonContest(uri: URI) extends AnyVal {
  def redisKey = s"polygonContest/$uri"
}

case class PolygonProblemShort(uri: URI) extends AnyVal {
  def redisKey = s"polygonProblem/$uri"
  def fullUri: URI = {
    new URIBuilder(s"${uri.toASCIIString}/problem.xml").build()
  }
}

case class PolygonProblemID(uri: URI, revision: Long) {
  def fileUri: URI = {
    new URIBuilder(uri).addParameter("revision", revision.toString).build()
  }

  def fullUri: URI = {
    new URIBuilder(s"${uri.toASCIIString}/problem.xml").addParameter("revision", revision.toString).build()
  }

  def redisKey = s"polygonProblem/${fullUri}"
}

case class ContestWithProblems(contest: ContestDescription, problems: Map[String, PolygonProblem])

trait PolygonContestClient {
  def getContest(contest: PolygonContestId): Future[ContestWithProblems]
}

trait PolygonProblemClient {
  def getProblem(contest: PolygonContestId, problem: String): Future[Option[Problem]]
}

case class PolygonProblemNotFoundException(problem: PolygonProblemShort) extends Throwable
case class PolygonContestNotFoundException(contest: PolygonContest) extends Throwable
case class PolygonProblemFileNotFoundException(problem: PolygonProblemID) extends Throwable

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

case class PolygonClient(service: Service[URI, Option[PolygonResponse]], store: Client,
                         polygonMap: Map[String, PolygonConfig], pdb: SanitizeDb, inv: InvokerSimpleApi)
  extends PolygonContestClient with PolygonProblemClient with Logging {

  private[this] val contestClient = ContestClient1(service, store)
  private[this] val problemClient = ProblemClient1(service, store)

  private[this] def resolve(contest: PolygonContestId) = {
    var r = polygonMap(contest.polygon).contest(contest.contestId)
    trace(s"resolve($contest) = $r")
    r
  }

  override def getContest(contest: PolygonContestId): Future[ContestWithProblems] = {
    trace(s"getContest: $contest")
    contestClient.refresh(resolve(contest)).flatMap { cdesc =>
      Future.collect(cdesc.problems.mapValues(PolygonProblemShort).mapValues(problemClient.refresh)).map { pmap =>
        trace(s"pmap: $pmap")
        pmap.mapValues(sanitize1).foreach(x => x._2.onFailure(error(s"$x", _)))
        ContestWithProblems(cdesc, pmap)
      }
    }.onFailure(error(s"getContest: $contest", _))
  }

  val serialSanitizer = new SerialHash[PolygonProblem, Problem]

  def maybeSanitize(p: PolygonProblem): Future[Problem] = {
    trace(s"maybeSanitize called: $p")
    pdb.getProblem(p).flatMap {
      case Some(x) => Future.value(x)
      case None =>
        pdb.ensureProblemFile(p, getProblemFile(p.toId)).flatMap { _ =>
          inv.sanitize(p).flatMap { m =>
            pdb.setProblem(p, m).map { pp =>
              pp
            }
          }
        }
    }
  }

  def sanitize1(p: PolygonProblem) = {
    trace(s"sanitize1: $p")
    serialSanitizer(p, () => maybeSanitize(p))
  }

  override def getProblem(contest: PolygonContestId, problem: String): Future[Option[Problem]] =
    contestClient(resolve(contest)).flatMap { cdesc =>
      Fu.liftOption(cdesc.problems.get(problem).map(PolygonProblemShort).map(problemClient)).flatMap {
        case None => Future.None
        case Some(p) =>
          sanitize1(p).map(Some(_))
      }
    }

  def getProblemFile(problem: PolygonProblemID): Future[Buf] =
    service(problem.fileUri).flatMap {
      case Some(r) => Future.value(r.response.content)
      case None => Future.exception(PolygonProblemFileNotFoundException(problem))
    }
}