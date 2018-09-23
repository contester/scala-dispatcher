package org.stingray.contester.dispatcher

import java.io.File
import java.sql.{ResultSet, Timestamp}

import akka.actor.ActorRef
import com.spingo.op_rabbit.PlayJsonSupport._
import com.spingo.op_rabbit.Message
import com.twitter.util.{Future, Return, Throw}
import grizzled.slf4j.Logging
import org.stingray.contester.common._
import org.stingray.contester.invokers.TimeKey
import org.stingray.contester.polygon.{PolygonContestId, PolygonProblemClient}
import org.stingray.contester.problems.Problem
import org.stingray.contester.testing._
import play.api.libs.json.{JsValue, Json, Writes}
import slick.jdbc.{GetResult, JdbcBackend}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future => ScalaFuture}
import scala.util.{Failure, Success}

trait Submit extends TimeKey with SubmitWithModule {
  def schoolMode: Boolean = false
}

case class SubmitObject(id: Int, contestId: Int, teamId: Int, problemId: String,
                        arrived: Timestamp, sourceModule: Module, override val schoolMode: Boolean, computer: Long,
                        polygonId: PolygonContestId)
  extends Submit {
  val timestamp = arrived
  override def toString =
    s"Submit(id=$id, contest=$contestId, team=$teamId, problem=$problemId, arrived: $arrived)"
}

case class FinishedTesting(submit: SubmitObject, testingId: Int, compiled: Boolean, passed: Int, taken: Int)

case object ProblemNotFoundError extends Throwable

class SubmitDispatcher(db: JdbcBackend#DatabaseDef, pdb: PolygonProblemClient, inv: SolutionTester,
                       store: TestingStore, rabbitMq: ActorRef, reportbase: String) extends Logging {
  import slick.driver.MySQLDriver.api._
  import org.stingray.contester.utils.Dbutil._

  implicit val getSubmitObject = GetResult(r =>
    SubmitObject(r.nextInt(), r.nextInt(), r.nextInt(), r.nextString(), r.nextTimestamp(),
    new ByteBufferModule(r.nextString(), r.nextBytes()), r.nextBoolean(), r.nextLong(), PolygonContestId(r.nextString())
    )
  )

  import org.stingray.contester.utils.Fu._

  private def getSubmit(id: Int): Future[Option[SubmitObject]] =
    db.run(
    sql"""
      select
      NewSubmits.ID, NewSubmits.Contest, NewSubmits.Team, NewSubmits.Problem,
      NewSubmits.Arrived, Languages.Ext, NewSubmits.Source, Contests.SchoolMode, NewSubmits.Computer,
      Contests.PolygonID
      from NewSubmits, Languages, Contests
      where NewSubmits.Contest = Languages.Contest and NewSubmits.SrcLang = Languages.ID
      and Contests.ID = NewSubmits.Contest
      and Contests.PolygonID != '' and NewSubmits.ID = $id""".as[SubmitObject]).map(_.headOption)

  private def markWith(id: Int, value: Int): Future[Int] =
    db.run(sqlu"update NewSubmits set Processed = $value where ID = $id")

  private def calculateTestingResult(m: SubmitObject, ti: Int, sr: SolutionTestingResult) = {
    val taken = sr.tests.length
    val passed = sr.tests.count(x => x._2.success)

    FinishedTesting(m, ti, sr.compilation.success, passed, taken)
  }

  implicit val submitObjectWrites = new Writes[SubmitObject] {
    override def writes(o: SubmitObject): JsValue =
      Json.obj(
        "id" -> o.id,
        "team" -> o.teamId,
        "contest" -> o.contestId,
        "problem" -> o.problemId,
        "schoolMode" -> o.schoolMode
      )
  }
  //implicit val finishedTestingFormat = Json.format[FinishedTesting]

  implicit val finishedTestingWrites = new Writes[FinishedTesting] {
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
          case Return(x) => markWith(id, 255).unit
          case Throw(x) => markWith(id, 254).unit
        }
      case None => Future.Done
    }

  private val reporter = new DBReporter(db)

  private def run(m: SubmitObject): Future[Unit] =
    pdb.getProblem(m.polygonId, m.problemId).flatMap {
      case Some(problem) =>
        CombinedResultReporter.allocate(reporter, new File(reportbase), m, problem.uri).flatMap {
          case (testingId, raw) =>
          val progress = new DBSingleResultReporter(db, m, testingId)
            val cprogress = new CombinedSingleProgress(progress, raw)
          inv(m, m.sourceModule, problem.problem, cprogress, m.schoolMode,
            store.submit(m.id, testingId),
            Map.empty, true
          ).flatMap { testingResult =>
            raw.finish(testingResult)
            val f = progress.finish(testingResult, m.id, testingId)
            f.onComplete { _ =>
              rabbitMq ! Message.exchange(calculateTestingResult(m, testingId, testingResult), exchange = "contester.submitdone")
            }
            f
          }
        }
      case None => Future.exception(ProblemNotFoundError)
    }
}
