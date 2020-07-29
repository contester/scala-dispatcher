package org.stingray.contester.dispatcher

import java.io.File

import akka.actor.ActorRef
import com.spingo.op_rabbit.Message
import com.spingo.op_rabbit.PlayJsonSupport._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.{Future, Return, Throw}
import play.api.Logging
import org.stingray.contester.common._
import org.stingray.contester.invokers.TimeKey
import org.stingray.contester.polygon.{PolygonContestId, PolygonProblemClient}
import org.stingray.contester.testing._
import play.api.libs.json.{JsValue, Json, Writes}
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future => ScalaFuture}

trait Submit extends TimeKey with SubmitWithModule {
  def schoolMode: Boolean = false
}

import com.github.nscala_time.time.Imports._

case class SubmitObject(id: Long, contestId: Int, teamId: Int, problemId: String,
                        arrived: DateTime, sourceModule: ByteBufferModule, override val schoolMode: Boolean, computer: Long,
                        polygonId: PolygonContestId)
  extends Submit {
  val timestamp = arrived
  override def toString =
    s"Submit(id=$id, contest=$contestId, team=$teamId, problem=$problemId, arrived: $arrived)"
}

case class FinishedTesting(submit: SubmitObject, testingId: Long, compiled: Boolean, passed: Int, taken: Int)

case object ProblemNotFoundError extends Throwable

class SubmitDispatcher(db: JdbcBackend#DatabaseDef, pdb: PolygonProblemClient, inv: SolutionTester,
                       store: TestingStore, rabbitMq: ActorRef, reportbase: String,
                       fsClient:Service[Request, Response], fsBaseUrl: String) extends Logging {
  import org.stingray.contester.utils.Fu._

  private def getSubmit(id: Int): Future[Option[SubmitObject]] = {
    import CPModel._
    import slick.jdbc.PostgresProfile.api._

    db.run(getSubmitByID(id).result.headOption).map(_.map {x =>
      SubmitObject(x._1, x._2, x._3, x._4, x._5, new ByteBufferModule(x._6, x._7), false, 0, PolygonContestId(x._8))
    })
  }

  private def markWith(id: Long): Future[Int] = {
    import CPModel._
    import slick.jdbc.PostgresProfile.api._

    db.run(submitTestedByID(id).update(true))
  }

  private def calculateTestingResult(m: SubmitObject, ti: Long, sr: SolutionTestingResult) = {
    val taken = sr.tests.length
    val passed = sr.tests.count(x => x._2.success)

    FinishedTesting(m, ti, sr.compilation.success, passed, taken)
  }

  implicit private val submitObjectWrites = new Writes[SubmitObject] {
    override def writes(o: SubmitObject): JsValue =
      Json.obj(
        "id" -> o.id,
        "team" -> o.teamId,
        "contest" -> o.contestId,
        "problem" -> o.problemId,
        "schoolMode" -> o.schoolMode
      )
  }

  implicit private val finishedTestingWrites = new Writes[FinishedTesting] {
    override def writes(o: FinishedTesting): JsValue =
      Json.obj(
        "submit" -> o.submit,
        "testingId" -> o.testingId,
        "compiled" -> o.compiled,
        "passed" -> o.passed,
        "taken" -> o.taken
      )
  }


  def runq(id: Int): ScalaFuture[Unit] =
    getSubmit(id).flatMap {
      case Some(submit) =>
        run(submit).transform {
          case Return(x) => markWith(id).unit
          case Throw(x) => markWith(id).unit
        }
      case None => Future.Done
    }

  private val reporter = new DBReporter(db)

  private def run(m: SubmitObject): Future[Unit] =
    pdb.getProblem(m.polygonId, m.problemId).flatMap {
      case Some(problem) =>
        CombinedResultReporter.allocate(reporter, new File(reportbase), m, problem.uri).flatMap {
          case (testingId, raw) =>
            logger.info(s"allocated testing: $testingId - $raw")
          val progress = new DBSingleResultReporter(db, m, testingId)
            val cprogress = new CombinedSingleProgress(progress, raw)
            val stSubmit = store.submit(m.id, testingId)
            ModuleUploader.upload(m.sourceModule, fsClient, fsBaseUrl, stSubmit.sourceModule)
            logger.info("before inv call")
          val xf = inv(m, m.sourceModule, problem.problem, cprogress, m.schoolMode,
            stSubmit,
            Map.empty, true
          ).flatMap { testingResult =>
            logger.info(s"testing result: $testingResult")
            raw.finish(testingResult)
            val f = progress.finish(testingResult, m.id, testingId)
            f.onComplete { _ =>
              rabbitMq ! Message.exchange(calculateTestingResult(m, testingId, testingResult), exchange = "contester.submitdone")
            }
            f
          }
            logger.info("after inv call")
            xf.onComplete { e =>
              logger.info(s"oc: $e")
            }
            xf
        }
      case None => Future.exception(ProblemNotFoundError)
    }
}
