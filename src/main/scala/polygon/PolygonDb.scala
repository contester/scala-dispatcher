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

import scala.xml.XML

case class PolygonContest(uri: URI) extends AnyVal

case class PolygonProblemID(uri: URI, revision: Option[Int]) {
  def fileUri: URI = {
    val r = new URIBuilder(uri)
    revision.map(rev => r.addParameter("revision", rev.toString)).getOrElse(r).build()
  }

  def fullUri: URI = {
    val r = new URIBuilder(uri.resolve("problem.xml"))
    revision.map(rev => r.addParameter("revision", rev.toString)).getOrElse(r).build()
  }
}

trait PolygonProblemClient {
  def getProblem(contest: PolygonContestId, problem: String): Future[Option[Problem]]
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