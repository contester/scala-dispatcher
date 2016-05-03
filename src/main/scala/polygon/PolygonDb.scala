package org.stingray.contester.polygon

import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

import com.twitter.finagle.Service
import com.twitter.finagle.redis.Client
import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.io.{BufInputStream, Charsets}
import com.twitter.util.{Duration, Future}
import org.apache.http.client.utils.URIBuilder
import org.stingray.contester.problems.Problem
import org.stingray.contester.utils.ScannerCache

import scala.xml.XML

case class PolygonContest(uri: URI) extends AnyVal {
  def redisKey = s"polygonContest/$uri"
}

case class PolygonProblemShort(uri: URI) extends AnyVal

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

case class PolygonProblemNotFoundException(problem: PolygonProblemID) extends Throwable
case class PolygonContestNotFoundException(contest: PolygonContest) extends Throwable

case class PolygonClient(service: Service[URI, Option[PolygonResponse]], store: Client) {
  private[this] def parseContest(content: String) =
    ContestDescription.parse(XML.loadString(content))

  private[this] def parseProblem(content: String) =
    PolygonProblem.parse(XML.loadString(content))

  def nearGetContest(contest: PolygonContest): Future[Option[ContestDescription]] =
    store.get(StringToChannelBuffer(contest.redisKey)).map { v =>
      v.map(x => parseContest(x.toString(Charsets.Utf8)))
    }

  def nearPutContest(contest: PolygonContest, content: String): Future[ContestDescription] = {
    val parsed = parseContest(content)
    store.set(StringToChannelBuffer(contest.redisKey), StringToChannelBuffer(content)).map(_ => parsed)
  }

  def farGetContest(contest: PolygonContest) =
    service(contest.uri).map {
      case Some(x) => x.response.contentString
      case None => throw PolygonContestNotFoundException(contest)
    }

  val contestScanner = new ScannerCache[PolygonContest, ContestDescription, String] {
    override def nearGet(key: PolygonContest): Future[Option[ContestDescription]] = nearGetContest(key)
    override def farGet(key: PolygonContest): Future[String] = farGetContest(key)
    override def nearPut(key: PolygonContest, value: String): Future[ContestDescription] = nearPutContest(key, value)
  }

  def nearGetProblem(problem: PolygonProblemID): Future[Option[PolygonProblem]] =
    store.get(StringToChannelBuffer(problem.redisKey)).map { v =>
      v.map(x => parseProblem(x.toString(Charsets.Utf8)))
    }

  def nearPutProblem(problem: PolygonProblemID, content: String): Future[PolygonProblem] = {
    val parsed = parseProblem(content)
    store.set(StringToChannelBuffer(problem.redisKey), StringToChannelBuffer(content)).map(_ => parsed)
  }

  def farGetProblem(problem: PolygonProblemID) =
    service(problem.fullUri).map {
      case Some(x) => x.response.contentString
      case None => throw PolygonProblemNotFoundException(problem)
    }
}

/*

Refresher/resolver:
refreshContest(cid: PolygonContest): Future[Option[ContestDescription]]
getContest(cid: PolygonContest): Future[Option[ContestDescription]]

refreshProblem



case class ContestWithProblems(contest: ContestDescription, problems: Map[String, PolygonProblem])

case class PolygonClient(store: Client, service: Service[URI, Option[PolygonResponse]]) {

  private def getContest(contest: PolygonContest): Future[ContestDescription] =
    store.get(StringToChannelBuffer(s"polygonContest/${contest.uri}")).flatMap {
      case Some(x) => Future.value(ContestDescription.parse(XML.loadString(x.toString(Charsets.Utf8))))
      case None => Future.sleep(Duration(2, TimeUnit.SECONDS)).flatMap(_ => getContest(contest))
    }

  private def fetchContests(contests: Seq[PolygonContest]) =
    Future.collect(contests.map { contest =>
      service(contest.uri).map { respOpt =>
        respOpt.map { resp =>
          contest -> (resp.response.contentString, ContestDescription.parse(XML.loadString(resp.response.contentString)))
        }
      }.handle {
        case _ => None
      }
    }).map(_.flatten.toMap)

  def fetchProblem(problem: PolygonProblemID): Future[Option[PolygonProblem]] =
    service(problem.fullUri).map { responseOption =>
      responseOption.map { response =>
        PolygonProblem.parse(XML.loadString(response.response.contentString))
      }
    }

  def getProblemFile(problem: PolygonProblemID): Future[Option[InputStream]] =
    service(problem.fileUri).map { respOpt =>
      respOpt.map { resp =>
        new BufInputStream(resp.response.content)
      }
    }
}
*/