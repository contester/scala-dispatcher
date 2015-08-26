package org.stingray.contester.dispatcher

import java.sql.{ResultSet, Timestamp}

import com.spingo.op_rabbit.PlayJsonSupport._
import com.spingo.op_rabbit.QueueMessage
import grizzled.slf4j.Logging
import org.stingray.contester.common._
import org.stingray.contester.invokers.TimeKey
import org.stingray.contester.polygon.{PolygonProblem, PolygonURL}
import org.stingray.contester.problems.Problem
import org.stingray.contester.testing._
import play.api.libs.json.{JsValue, Json, Writes}
import slick.jdbc.{GetResult, JdbcBackend}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

trait Submit extends TimeKey with SubmitWithModule {
  def schoolMode: Boolean = false
}

case class SubmitObject(id: Int, contestId: Int, teamId: Int, problemId: String,
                        arrived: Timestamp, sourceModule: Module, override val schoolMode: Boolean, computer: Long)
  extends Submit {
  val timestamp = arrived
  override def toString =
    "Submit(%d, %d, %s, %s)".format(id, contestId, problemId, arrived)
}

case class FinishedTesting(submit: SubmitObject, testingId: Int, compiled: Boolean, passed: Int, taken: Int)

class SubmitDispatcher(parent: DbDispatcher, db: JdbcBackend#DatabaseDef) extends Logging {
  import slick.driver.MySQLDriver.api._
  import org.stingray.contester.utils.Dbutil._

  implicit val getSubmitObject = GetResult(r =>
    SubmitObject(r.nextInt(), r.nextInt(), r.nextInt(), r.nextString(), r.nextTimestamp(),
    new ByteBufferModule(r.nextString(), r.nextBytes()), r.nextBoolean(), r.nextLong()
    )
  )

  def getSubmit(id: Int) =
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

  def markWith(id: Int, value: Int) =
    db.run(sqlu"update NewSubmits set Processed = $value where ID = $id")

  import org.stingray.contester.utils.Fu._
  def createTestingInfo(reporter: DBReporter, m: SubmitObject): Future[TestingInfo] =
    parent.getPolygonProblem(m.contestId, m.problemId).flatMap { polygonProblem =>
      reporter.allocateTesting(m.id, polygonProblem.handle.uri.toString).flatMap { testingId =>
        new RawLogResultReporter(parent.basePath, m).start.map { _ =>
          new TestingInfo(testingId, polygonProblem.handle.uri.toString, Seq())
        }
      }
    }

  def getTestingInfo(reporter: DBReporter, m: SubmitObject) =
    reporter.getAnyTestingAndState(m.id).flatMap(_.map(Future.successful).getOrElse(createTestingInfo(reporter, m)))

  def calculateTestingResult(m: SubmitObject, ti: TestingInfo, sr: SolutionTestingResult) = {
    val taken = sr.tests.length
    val passed = sr.tests.count(x => x._2.success)

    FinishedTesting(m, ti.testingId, sr.compilation.success, passed, taken)
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

  def runq(id: Int) =
    getSubmit(id).flatMap { submitOption =>
      run(submitOption.get).andThen {
        case Success(s) => markWith(id, 255)
        case Failure(f) => markWith(id, 254)
      }
    }

  // main test entry point
  def run(m: SubmitObject) = {
    val reporter = new DBReporter(db)
    getTestingInfo(reporter, m).flatMap { testingInfo =>
      reporter.registerTestingOnly(m, testingInfo.testingId).flatMap { _ =>
        val combinedProgress = new CombinedSingleProgress(
          new DBSingleResultReporter(db, m, testingInfo.testingId),
          new RawLogResultReporter(parent.basePath, m))

        parent.pdata.getPolygonProblem(PolygonURL(testingInfo.problemId))
          .flatMap(x => parent.pdata.sanitizeProblem(x)).flatMap { problem =>
          parent.invoker(m, m.sourceModule, problem, combinedProgress, m.schoolMode,
            new InstanceSubmitTestingHandle(parent.storeId, m.id, testingInfo.testingId),
            testingInfo.state.toMap.mapValues(new RestoredResult(_))).flatMap { (sr: SolutionTestingResult) =>
            combinedProgress.db.finish(sr, m.id, testingInfo.testingId).zip(combinedProgress.raw.finish(sr))
            .map {_ =>
              parent.rabbitMq ! QueueMessage(calculateTestingResult(m, testingInfo, sr), queue = "contester.finished")
              ()
            }
          }
        }
      }
    }
  }
}

