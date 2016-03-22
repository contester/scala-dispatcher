package org.stingray.contester.problems

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Status, RequestBuilder, Response, Request}
import com.twitter.util.Future
import org.apache.http.client.utils.{URIBuilder, URLEncodedUtils}
import play.api.libs.json.{JsSuccess, Reads, Json}

case class SimpleProblemDbException(reason: String) extends Throwable(reason)

case class SimpleProblemManifest(id: String, revision: Int, testCount: Int, timeLimitMicros: Long, memoryLimit: Long,
                                 stdio: Boolean, testerName: String, answers: Set[Int], interactorName: Option[String],
                                 combinedHash: Option[String])



object SimpleProblemManifest {
  implicit val formatSimpleProblemManifest = Json.format[SimpleProblemManifest]
}

object SimpleProblemDb {
  def parseSimpleProblemManifest(what: String): Option[SimpleProblemManifest] = {
    Json.parse(what).validate[Seq[SimpleProblemManifest]] match {
      case s: JsSuccess[Seq[SimpleProblemManifest]] => Some(s.get.head)
      case _ => None
    }
  }

}

class SimpleProblemDb(url: String, client: Service[Request, Response]) extends ProblemDb {
  import SimpleProblemDb._
  override def setProblem(problem: ProblemID, manifest: ProblemManifest): Future[Problem] = ???

  override def getProblem(problem: ProblemID): Future[Option[Problem]] = {
    val ub = new URIBuilder(url+"problem/get/").addParameter("id", problem.pid).addParameter("revision", problem.revision.toString).build().toASCIIString
    val request = RequestBuilder().url(ub).buildGet()
    client(request).flatMap { r =>
      r.status match {
        case Status.Ok =>
          //Future.value(parseSimpleProblemManifest(r.contentString))
          Future.None
        case Status.NotFound =>
          Future.None
        case _ => Future.exception(SimpleProblemDbException(r.status.reason))
      }
    }
  }

  override def getMostRecentProblem(problem: ProblemHandle): Future[Option[Problem]] = ???
}