package org.stingray.contester.testing

import org.stingray.contester.common.{CompileResult, Result, TestResult}
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime
import org.stingray.contester.dispatcher.SubmitObject
import java.io.File
import java.nio.charset.StandardCharsets

import org.apache.commons.io.FileUtils
import org.stingray.contester.engine.CustomTestResult
import slick.jdbc.MySQLProfile.api._
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext.Implicits.global
import org.stingray.contester.utils.Dbutil._

import scala.concurrent.Future

case class SolutionTestingResult(compilation: CompileResult, tests: Seq[(Int, Result)])
case class CustomTestingResult(compilation: CompileResult, test: Option[CustomTestResult])

trait SingleProgress {
  def compile(r: CompileResult): Future[Unit]
  def test(id: Int, r: TestResult): Future[Unit]
}

class CombinedSingleProgress(val db: DBSingleResultReporter, val raw: RawLogResultReporter) extends SingleProgress {
  def compile(r: CompileResult): Future[Unit] = db.compile(r).zip(raw.compile(r)).map(_ => ())

  def test(id: Int, r: TestResult): Future[Unit] = db.test(id, r).zip(raw.test(id, r)).map(_ => ())
}

object CombinedResultReporter {
  val fmt = ISODateTimeFormat.dateTime()

  def ts =
    "[" + fmt.print(new DateTime()) + "]"

  def allocate(db: DBReporter, prefix: File, submit: SubmitObject, problemUri: String): Future[(Int, RawLogResultReporter)] =
    db.allocateAndRegister(submit, problemUri).zip {
      val r = RawLogResultReporter(prefix, submit)
      r.start
    }
}

class DBSingleResultReporter(client: JdbcBackend#DatabaseDef, val submit: SubmitObject, val testingId: Int) extends SingleProgress {
  def compile(r: CompileResult): Future[Unit] = {
    val cval = if (r.success) 1 else 0
      client.run(
        sqlu"""insert into Results (UID, Submit, Result, Test, Timex, Memory, TesterOutput, TesterError)
           values ($testingId, ${submit.id}, ${r.status.value}, 0, 0, 0, ${new String(r.stdOut, "cp866")},
            ${new String(r.stdErr, "cp866")})""").zip(
          client.run(sqlu"Update Submits set Compiled = ${cval} where ID = ${submit.id}"))
        .map(_ => ())
  }

  def test(testId: Int, result: TestResult): Future[Unit] =
    client.run(sqlu"""Insert into Results (UID, Submit, Result, Test, Timex, Memory, Info, TesterOutput,
        TesterError, TesterExitCode) values ($testingId, ${submit.id}, ${result.status.value}, $testId,
        ${result.solution.time / 1000}, ${result.solution.memory}, ${result.solution.returnCode},
        ${new String(result.getTesterOutput, "cp1251")}, ${new String(result.getTesterError, "windows-1251")},
        ${result.getTesterReturnCode})""").map(_ => ())

  private def finishTesting(testingId: Int) =
    client.run(sqlu"update Testings set Finish = NOW() where ID = $testingId").map(_ => ())

  private def finishSubmit(submitId: Int, result: SolutionTestingResult) =
    client.run(
      sqlu"""Update Submits set Finished = 1, Taken = ${result.tests.size},
            Passed = ${result.tests.count(_._2.success)} where ID = $submitId""").map(_ => ())

  /**
   * Close the testing and update submits table
   * @param result
   * @param submitId
   * @param testingId
   * @return
   */
  def finish(result: SolutionTestingResult, submitId: Int, testingId: Int): Future[Unit] =
    finishTesting(testingId).zip(finishSubmit(submitId, result)).map(_ => ())
}

case class TestingInfo(testingId: Int, problemId: String, state: Seq[(Int, Int)])

class DBReporter(val client: JdbcBackend#DatabaseDef) {
  /**
   * Allocate new testing ID in the database.
   * @param submitId Submit ID.
   * @param problemId Problem ID/Url.
   * @return Future testing ID.
   */
  def allocateTesting(submitId: Int, problemId: String): Future[Int] =
    client.run(sqlu"""Insert into Testings (Submit, ProblemID, Start) values ($submitId, $problemId, NOW())"""
      .andThen(sql"select LAST_INSERT_ID()".as[Int]).withPinnedSession).map(_.head)

  /**
   * Update Submits table for given submit with given testing ID.
   * @param submit Submit object to consider.
   * @param testingId Testing ID to update with.
   * @return
   */
  def registerTestingOnly(submit: SubmitObject, testingId: Int) =
    client.run(
      sqlu"""Replace Submits (Contest, Arrived, Team, Task, ID, Ext, Computer, TestingID, Touched, Finished)
      values (${submit.contestId}, ${submit.arrived}, ${submit.teamId}, ${submit.problemId}, ${submit.id},
      ${submit.sourceModule.moduleType}, ${submit.computer}, $testingId, NOW(), 0)""")

  def allocateAndRegister(submit: SubmitObject, problemId: String): Future[Int] =
    allocateTesting(submit.id, problemId).flatMap { testingId =>
      registerTestingOnly(submit, testingId).map { _ => testingId }
    }

  // Get testing ID from submit row, or None
  private def getTestingIdFromSubmit(submitId: Int): Future[Option[Int]] =
    client.run(sql"select TestingID from Submits where Id = $submitId".as[Option[Int]]).map(_.headOption.flatten)

  // Get active testing from testingId, or None
  private def getTestingFromSubmitAndId(submitId: Int, testingId: Int): Future[Option[(Int, String)]] =
    client.run(
      sql"""select ID, ProblemID from Testings where Finish is null and ProblemID is not null and
           Submit = $submitId and ID = $testingId""".as[(Int, String)]).map(_.headOption)

  // Get most recent active testing
  private def getTestingFromSubmit(submitId: Int): Future[Option[(Int, String)]] =
    client.run(
      sql"select ID, ProblemID from Testings where Finish is null and ProblemID is not null and Submit = $submitId order by ID desc limit 1"
        .as[(Int, String)]).map(_.headOption)

  private def getAnyTesting(submitId: Int): Future[Option[(Int, String)]] =
    getTestingIdFromSubmit(submitId).flatMap { optTestingId =>
      optTestingId.map(getTestingFromSubmitAndId(submitId, _)).getOrElse(Future.successful(None))
    }.flatMap { optTesting =>
        if (optTesting.isEmpty)
          getTestingFromSubmit(submitId)
        else
          Future.successful(optTesting)
    }

  private def getTestingState(testingId: Int): Future[Seq[(Int, Int)]] =
    client.run(sql"select Test, Result from Results where UID = $testingId and Test > 0".as[(Int, Int)])

  def getAnyTestingAndState(submitId: Int): Future[Option[TestingInfo]] =
    getAnyTesting(submitId).flatMap(_.map { testing =>
      getTestingState(testing._1).map(x => Some(new TestingInfo(testing._1, testing._2, x)))
    }.getOrElse(Future.successful(None)))
}

case class RawLogResultReporter(base: File, val submit: SubmitObject) extends SingleProgress {
  lazy val terse = new File(base, submit.id.toString)
  lazy val detailed = new File(base, submit.id.toString + ".proto")

  private def rawlog(short: String, pb: String = "") =
    Future {
      import collection.JavaConverters._
      val ts = CombinedResultReporter.ts
      FileUtils.writeStringToFile(terse, ts + " " + short + "\n", StandardCharsets.UTF_8, true)
      FileUtils.writeStringToFile(detailed, ts + " " + short + "\n", StandardCharsets.UTF_8, true)
      if (!pb.isEmpty) {
        FileUtils.writeLines(detailed, pb.linesIterator.map(ts + "     " + _).toList.asJava, true)
      }
    }

  def compile(result: CompileResult): Future[Unit] =
    rawlog(s"  $result", result.toProto.toProtoString)

  def test(id: Int, result: TestResult): Future[Unit] =
    rawlog(s"  Test $id: $result", result.toProto.toProtoString)

  def finish(result: SolutionTestingResult): Future[Unit] =
    rawlog(s"Finished testing $submit")

  def start =
    rawlog(s"Started testing $submit").map(_ => this)
}
