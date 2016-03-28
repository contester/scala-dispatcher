package org.stingray.contester.problems

import java.net.URI

import com.twitter.finagle.{Http, Service}
import com.twitter.finagle.http.{Request, RequestBuilder, Response, Status}
import com.twitter.util.Future
import org.apache.http.client.utils.{URIBuilder, URLEncodedUtils}
import org.stingray.contester.invokers.Sandbox
import play.api.libs.json.{JsSuccess, Json, Reads}

case class SimpleProblemDbException(reason: String) extends Throwable(reason)

class SimpleProblemTest(problem: SimpleProblem, val testId: Int) extends Test with TestLimits {
  override def getLimits(moduleType: String): TestLimits = this

  override def key: Future[Option[String]] =
    Future.value(Some(problem.id.testPrefix(testId)))

  private[this] def putAsset(sandbox: Sandbox, what: String, where: String) =
    sandbox.putGridfs("filer:" + problem.baseUrl + what, where).map { r =>
      if (r.isEmpty) throw new TestAssetNotFoundException(what)
    }

  def prepareInput(sandbox: Sandbox): Future[Unit] =
    putAsset(sandbox, problem.id.inputName(testId), "input.txt")

  def prepareTester(sandbox: Sandbox): Future[Unit] =
    if (problem.m.answers(testId))
      putAsset(sandbox, problem.id.answerName(testId), "answer.txt")
    else Future.Done

  def prepareTesterBinary(sandbox: Sandbox): Future[String] =
    putAsset(sandbox, problem.id.checkerName, problem.m.testerName).map(_ => problem.m.testerName)

  def prepareInteractorBinary(sandbox: Sandbox): Future[String] =
    problem.m.interactorName.map { i =>
      putAsset(sandbox, problem.id.interactorName, i).map(_ => i)
    }.getOrElse(Future.exception(new TestAssetNotFoundException(problem.id.interactorName)))

  override def interactive: Boolean = problem.m.interactorName.isDefined

  override def stdio: Boolean = problem.m.stdio.getOrElse(false)

  override def memoryLimit: Long = problem.m.memoryLimit

  override def timeLimitMicros: Long = problem.m.timeLimitMicros
}

case class SimpleProblemManifest(id: String, revision: Int, testCount: Int, timeLimitMicros: Long, memoryLimit: Long,
                                 stdio: Option[Boolean], testerName: String, answers: Set[Int], interactorName: Option[String],
                                 combinedHash: Option[String])

class SimpleProblem(val baseUrl: String, val m: SimpleProblemManifest, val id: ProblemID) extends Problem {
  /**
    * Override this method to provide sequence of tests.
    *
    * @return Sequence of tests.
    */
  override protected def tests: Seq[Int] = 1 to m.testCount

  /**
    * Override this method to provide tests themselves.
    *
    * @param key Test ID.
    * @return Test.
    */
  override def getTest(key: Int): Test = new SimpleProblemTest(this, key)
}

object SimpleProblemManifest {
  implicit val formatSimpleProblemManifest = Json.format[SimpleProblemManifest]
}

object SimpleProblemDb {
  def parseSimpleProblemManifest(what: String): Option[SimpleProblemManifest] = {
    Json.parse(what).validate[Seq[SimpleProblemManifest]] match {
      case s: JsSuccess[Seq[SimpleProblemManifest]] => Some(s.get.head)
      case x =>
        println(x)
        None
    }
  }

  def apply(url: String) = {
    val parsed = new URI(url)
    val dest = if (parsed.getPort != -1) parsed.getHost else s"${parsed.getHost}:${parsed.getPort}"
    val client = Http.client.newService(dest)
    new SimpleProblemDb(url, client)
  }
}

class SimpleProblemDb(url: String, client: Service[Request, Response]) extends ProblemServerInterface {
  import SimpleProblemDb._

  private def receiveProblem(url: String): Future[Option[Problem]] = {
    val request = RequestBuilder().url(url).buildGet()
    client(request).flatMap { r =>
      r.status match {
        case Status.Ok =>
          Future.value(parseSimpleProblemManifest(r.contentString).map { found =>
            val pid = new SimpleProblemID(getSimpleUrlId(new URI(found.id)), found.revision)
            new SimpleProblem(url + "fs/", found, pid)
          })
        case Status.NotFound =>
          Future.None
        case _ => Future.exception(SimpleProblemDbException(r.status.reason))
      }
    }
  }

  def getPathPart(url: URI) =
    url.getPath.stripPrefix("/").stripSuffix("/")

  def getSimpleUrlId(url: URI) =
    (url.getScheme :: url.getHost :: (if (url.getPort != -1) url.getPort.toString :: getPathPart(url) :: Nil else getPathPart(url) :: Nil)).mkString("/")


  override def getMostRecentProblem(problem: ProblemHandle): Future[Option[Problem]] = {
    receiveProblem(new URIBuilder(url+"problem/get/")
      .addParameter("id", problem.uri.toASCIIString)
      .build().toASCIIString)
  }
}