package org.stingray.contester.problems

import java.net.URI

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, RequestBuilder, Response, Status}
import com.twitter.io.Buf
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.apache.http.client.utils.URIBuilder
import org.stingray.contester.invokers.Sandbox
import org.stingray.contester.utils.CachedConnectionHttpService
import play.api.libs.json._


case class SimpleProblemDbException(reason: String) extends Throwable(reason)

class SimpleProblemTest(problem: SimpleProblem, val testId: Int) extends Test with TestLimits {
  override def getLimits(moduleType: String): TestLimits = this

  override def toString: String = s"${problem.m.handle}/${problem.m.revision}-$testId"

  private[this] def putAsset(sandbox: Sandbox, what: String, where: String) =
    sandbox.putGridfs(what, where).map { r =>
      if (r.isEmpty) throw new TestAssetNotFoundException(what)
    }

  def prepareInput(sandbox: Sandbox): Future[Unit] =
    putAsset(sandbox, problem.assets.inputName(testId), "input.txt")

  def prepareTester(sandbox: Sandbox): Future[Unit] =
    if (problem.m.answers(testId))
      putAsset(sandbox, problem.assets.answerName(testId), "answer.txt")
    else Future.Done

  def prepareTesterBinary(sandbox: Sandbox): Future[String] =
    putAsset(sandbox, problem.assets.checkerName, problem.m.testerName).map(_ => problem.m.testerName)

  def prepareInteractorBinary(sandbox: Sandbox): Future[String] =
    problem.m.interactorName.map { i =>
      putAsset(sandbox, problem.assets.interactorName, i).map(_ => i)
    }.getOrElse(Future.exception(new TestAssetNotFoundException(problem.assets.interactorName)))

  override def interactive: Boolean = problem.m.interactorName.isDefined

  override def stdio: Boolean = problem.m.stdio

  override def memoryLimit: Long = problem.m.memoryLimit

  override def timeLimitMicros: Long = problem.m.timeLimitMicros
}

case class SimpleProblemManifest(id: String, revision: Long, testCount: Int, timeLimitMicros: Long, memoryLimit: Long,
                                 stdio: Boolean, testerName: String, answers: Set[Int], interactorName: Option[String]) extends ProblemHandleWithRevision {
  def handle = id
}

case class SimpleProblem(val m: SimpleProblemManifest, val assets: ProblemAssetInterface) extends Problem {
  override protected def tests: Seq[Int] = 1 to m.testCount
  override def getTest(key: Int): Test = new SimpleProblemTest(this, key)
}

object SimpleProblemManifest {
  //implicit val formatSimpleProblemManifest = Json.format[SimpleProblemManifest]
  import play.api.libs.functional.syntax._
  val readsSimpleProblemManifestBuilder = (
    (JsPath \ "id").read[String] and
      (JsPath \ "revision").read[Long] and
      (JsPath \ "testCount").read[Int] and
      (JsPath \ "timeLimitMicros").read[Long] and
      (JsPath \ "memoryLimit").read[Long] and
      (JsPath \ "stdio").readNullable[Boolean].map(_.getOrElse(false)) and
      (JsPath \ "testerName").readNullable[String].map(_.getOrElse("tester.exe")) and
      (JsPath \ "answers").readNullable[List[Int]].map(_.getOrElse(Nil).toSet) and
      (JsPath \ "interactorName").readNullable[String]
    )

  implicit val readsSimpleProblemManifest: Reads[SimpleProblemManifest] =
    readsSimpleProblemManifestBuilder.apply(SimpleProblemManifest.apply _)

  implicit val writesSimpleProblemManifest = new Writes[SimpleProblemManifest] {
    override def writes(o: SimpleProblemManifest): JsValue = {
      val f = Json.obj("id" -> o.id,
        "revision" -> o.revision,
        "testCount" -> o.testCount,
        "timeLimitMicros" -> o.timeLimitMicros,
        "memoryLimit" -> o.memoryLimit,
        "stdio" -> o.stdio,
        "testerName" -> o.testerName,
        "answers" -> o.answers.toList)
      if (o.interactorName.isDefined) {
        f ++ Json.obj("interactorName" -> o.interactorName)
      } else f
    }
  }
}

object SimpleProblemDb extends Logging {
  def parseSimpleProblemManifest(what: String): Option[SimpleProblemManifest] = {
    if (what.isEmpty)
      None
    else
    Json.parse(what).validate[Seq[SimpleProblemManifest]] match {
      case s: JsSuccess[Seq[SimpleProblemManifest]] => Some(s.get.head)
      case x =>
        error(s"parsing $x")
        None
    }
  }

  def apply(url: String) = {
    val parsed = new URI(url)
    val client = CachedConnectionHttpService(parsed)
    new SimpleProblemDb(url, client)
  }
}

case class ProblemArchiveUploadException(x: AnyRef) extends Throwable

class SimpleProblemDb(val baseUrl: String, client: Service[Request, Response]) extends ProblemServerInterface with SanitizeDb with Logging {
  import SimpleProblemDb._

  private def receiveProblem(url: String): Future[Option[Problem]] = {
    val request = RequestBuilder().url(url).buildGet()
    client(request).flatMap { r =>
      r.status match {
        case Status.Ok =>
          Future.value(parseSimpleProblemManifest(r.contentString).map { found =>
            SimpleProblem(found, StandardProblemAssetInterface(baseUrl, ProblemURI.getStoragePrefix(found)))
          })
        case Status.NotFound =>
          trace(s"problem not found: $url")
          Future.None
        case _ =>
          error(s"receiveProblem($url): $r")
          Future.exception(SimpleProblemDbException(r.status.reason))
      }
    }
  }

  override def getMostRecentProblem(problem: ProblemHandle): Future[Option[Problem]] = {
    receiveProblem(new URIBuilder(baseUrl+"problem/get/")
      .addParameter("id", problem.handle)
      .build().toASCIIString)
  }

  private def checkProblemArchive(problemArchiveName: String) = {
    val request = RequestBuilder().url(baseUrl + "fs/" + problemArchiveName).buildHead()
    client(request).map { r =>
      r.status match {
        case Status.Ok => true
        case _ => false
      }
    }
  }

  private def uploadProblemArchive(problemArchiveName: String, is: Buf) = {
    val request = RequestBuilder().url(baseUrl + "fs/" + problemArchiveName).buildPut(is)
    client(request).flatMap { r =>
      r.status match {
        case Status.Ok => Future.Done
        case x => Future.exception(ProblemArchiveUploadException(x))
      }
    }
  }

  override def setProblem(manifest: SimpleProblemManifest): Future[Problem] = {
    val request = RequestBuilder()
      .url(new URIBuilder(baseUrl+"problem/set/")
        .addParameter("id", manifest.id)
        .addParameter("revision", manifest.revision.toString).build().toASCIIString).buildPost(Buf.Utf8(Json.toJson(manifest).toString()))
    client(request).flatMap { r =>
      r.status match {
        case Status.Ok => Future.value(SimpleProblem(manifest,
          StandardProblemAssetInterface(baseUrl, ProblemURI.getStoragePrefix(manifest))))
        case _ => Future.exception(ProblemArchiveUploadException(manifest))
      }
    }
  }

  override def getProblem(problem: ProblemHandleWithRevision): Future[Option[Problem]] = {
    trace(s"Getting problem: $problem")
    receiveProblem(new URIBuilder(baseUrl + "problem/get/")
      .addParameter("id", problem.handle)
      .addParameter("revision", problem.revision.toString)
      .build().toASCIIString)
  }

  override def ensureProblemFile(problemArchiveName: String, getFn: => Future[Buf]): Future[Unit] =
    checkProblemArchive(problemArchiveName).flatMap {
      case true => Future.Done
      case false => getFn.flatMap { is =>
        uploadProblemArchive(problemArchiveName, is)
      }
    }
}
