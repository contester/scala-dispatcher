package org.stingray.contester.polygon

import java.net.URI

import com.twitter.finagle.Service
import com.twitter.finagle.redis.Client
import com.twitter.finagle.redis.util.{BufToString, StringToBuf}
import com.twitter.io.Buf
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.apache.http.client.utils.URIBuilder
import org.stingray.contester.engine.InvokerSimpleApi
import org.stingray.contester.problems._
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
  def getProblem(contest: PolygonContestId, problem: String): Future[Option[ProblemWithURI]]
}

case class ProblemWithURI(uri: String, problem: Problem)

case class PolygonProblemNotFoundException(problem: PolygonProblemShort) extends Throwable(problem.toString)
case class PolygonContestNotFoundException(contest: PolygonContest) extends Throwable(contest.toString)
case class PolygonProblemFileNotFoundException(problem: PolygonProblemID) extends Throwable(problem.toString)

case class ContestClient1(service: Service[URI, Option[PolygonResponse]], store: Client)
  extends ScannerCache[PolygonContest, ContestDescription, String]{
  def parse(key: PolygonContest, content: String) =
    ContestDescription.parse(XML.loadString(content), key.uri)

  def nearGet(contest: PolygonContest): Future[Option[String]] =
    store.get(StringToBuf(contest.redisKey)).map(_.map(BufToString(_)))

  override def nearPut(key: PolygonContest, value: String): Future[Unit] =
    store.set(StringToBuf(key.redisKey), StringToBuf(value))

  def farGet(contest: PolygonContest): Future[String] =
    service(contest.uri).map {
      case Some(x) => x.response.contentString
      case None => throw PolygonContestNotFoundException(contest)
    }
}

case class ProblemClient1(service: Service[URI, Option[PolygonResponse]], store: Client)
  extends ScannerCache[PolygonProblemShort, PolygonProblem, String] {
  def parse(key: PolygonProblemShort, content: String) =
    PolygonProblem.parse(XML.loadString(content))

  override def nearGet(key: PolygonProblemShort): Future[Option[String]] =
    store.get(StringToBuf(key.redisKey)).map(_.map(BufToString(_)))

  override def farGet(key: PolygonProblemShort): Future[String] =
    service(key.fullUri).map {
      case Some(x) => x.response.contentString
      case None => throw PolygonProblemNotFoundException(key)
    }

  override def nearPut(key: PolygonProblemShort, value: String): Future[Unit] =
    store.set(StringToBuf(key.redisKey), StringToBuf(value))
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
    contestClient.refresh(resolve(contest)).flatMap { cdesc =>
      Future.collect(cdesc.problems.mapValues(PolygonProblemShort).mapValues(problemClient.refresh)).map { pmap =>
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
        pdb.ensureProblemFile(Assets.archiveName(ProblemURI.getStoragePrefix(p)), getProblemFile(p.toId)).flatMap { _ =>
          inv.sanitize(p, StandardProblemAssetInterface(pdb.baseUrl, ProblemURI.getStoragePrefix(p))).flatMap { m =>
            pdb.setProblem(m).map { pp =>
              pp
            }
          }
        }
    }
  }

  def sanitize1(p: PolygonProblem) = {
    serialSanitizer(p, () => maybeSanitize(p))
  }

  override def getProblem(contest: PolygonContestId, problem: String): Future[Option[ProblemWithURI]] =
    contestClient(resolve(contest)).flatMap { cdesc =>
      Fu.liftOption(cdesc.problems.get(problem).map(PolygonProblemShort).map(problemClient)).flatMap {
        case None => Future.None
        case Some(p) =>
          trace(s"${p.uri.toASCIIString} - ${p.revision}")
          sanitize1(p).map(x => Some(ProblemWithURI(p.uri.toASCIIString + s"?revision=${p.revision}", x)))
      }
    }

  def getProblemFile(problem: PolygonProblemID): Future[Buf] =
    service(problem.fileUri).flatMap {
      case Some(r) => Future.value(r.response.content)
      case None => Future.exception(PolygonProblemFileNotFoundException(problem))
    }
}