package org.stingray.contester.testing

import org.stingray.contester.common.{CompileResult, Result, TestResult}
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime
import org.stingray.contester.dispatcher.{CPModel, SubmitObject}
import java.io.File
import java.nio.charset.StandardCharsets

import org.apache.commons.io.FileUtils
import org.stingray.contester.engine.CustomTestResult
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class SolutionTestingResult(compilation: CompileResult, tests: Seq[(Int, Result)])
case class CustomTestingResult(compilation: CompileResult, test: Option[CustomTestResult])

trait SingleProgress {
  def compile(r: CompileResult): Future[Unit]
  def test(id: Int, r: TestResult): Future[Unit]
}

trait GenericReporterFactory {
  def start(submit: SubmitObject, problemID: String): Future[StartedReporter]
}

trait ClosableReporter {
  def close(): Future[Unit]
}

trait StartedReporter {
  def compile(compileResult: CompileResult): Future[TestingReporter]
}

trait TestingReporter {
  def test(id: Int, testResult: TestResult): Future[Unit]
  def finish()
}

class CombinedSingleProgress(val db: DBSingleResultReporter, val raw: RawLogResultReporter) extends SingleProgress {
  def compile(r: CompileResult): Future[Unit] = db.compile(r).zip(raw.compile(r)).map(_ => ())

  def test(id: Int, r: TestResult): Future[Unit] = db.test(id, r).zip(raw.test(id, r)).map(_ => ())
}

object CombinedResultReporter {
  val fmt = ISODateTimeFormat.dateTime()

  def ts =
    "[" + fmt.print(new DateTime()) + "]"

  def allocate(db: DBReporter, prefix: File, submit: SubmitObject, problemUri: String): Future[(Long, RawLogResultReporter)] =
    db.allocateAndRegister(submit, problemUri).zip {
      val r = RawLogResultReporter(prefix, submit)
      r.start
    }
}

class DBReporterFactory(client: JdbcBackend#DatabaseDef) extends GenericReporterFactory {
  override def start(submit: SubmitObject, problemID: String): Future[StartedReporter] =
    Future.successful(new DBStartedReporter(client, submit, problemID))
}

class DBStartedReporter(client: JdbcBackend#DatabaseDef, submit: SubmitObject, problemID: String) extends StartedReporter {
  import CPModel._
  import slick.jdbc.PostgresProfile.api._

  override def compile(compileResult: CompileResult): Future[TestingReporter] = {
    val allocTesting = (testings.map(x => (x.submit, x.problemURL)) returning testings.map(_.id)) += (submit.id, problemID)

    client.run(allocTesting.flatMap { testingID =>
      val addResult = results.map(x => (x.testingID, x.resultCode, x.testID, x.timeMs, x.memoryBytes, x.testerOutput, x.testerError)) += (
        testingID, compileResult.status.value, 0, compileResult.time / 1000, compileResult.memory, compileResult.stdOut, compileResult.stdErr)
      (submits.filter(_.id === submit.id).map(_.testingID).update(testingID) zip addResult).map(_ => testingID)
    }).map { testingID =>
      new DBTestingReporter(client, submit, testingID)
    }
  }
}

class DBTestingReporter(client: JdbcBackend#DatabaseDef, val submit: SubmitObject, val testingId: Long) extends TestingReporter {
  import CPModel._
  import slick.jdbc.PostgresProfile.api._

  override def test(testId: Int, result: TestResult): Future[Unit] = {
    client.run(results.map(x => (x.testingID, x.resultCode, x.testID, x.timeMs, x.memoryBytes, x.returnCode, x.testerOutput, x.testerError, x.testerReturnCode)) += (
      testingId, result.status.value, testId, result.solution.time / 1000, result.solution.memory, result.solution.returnCode,
      result.getTesterOutput, result.getTesterError, result.getTesterReturnCode
    )).map(_ => ())
  }
  override def finish(): Unit = ???
}

class DBSingleResultReporter(client: JdbcBackend#DatabaseDef, val submit: SubmitObject, val testingId: Long) extends SingleProgress {
  import CPModel._
  import slick.jdbc.PostgresProfile.api._

  def compile(r: CompileResult): Future[Unit] = {
      client.run(results.map(x => (x.testingID, x.resultCode, x.testID, x.timeMs, x.memoryBytes, x.testerOutput, x.testerError)) += (
      testingId, r.status.value, 0, r.time / 1000, r.memory, r.stdOut, r.stdErr)).map(_ => ())
  }

  def test(testId: Int, result: TestResult): Future[Unit] =
    client.run(results.map(x => (x.testingID, x.resultCode, x.testID, x.timeMs, x.memoryBytes, x.returnCode, x.testerOutput, x.testerError, x.testerReturnCode)) += (
      testingId, result.status.value, testId, result.solution.time / 1000, result.solution.memory, result.solution.returnCode,
      result.getTesterOutput, result.getTesterError, result.getTesterReturnCode
    )).map(_ => ())

  /**
   * Close the testing and update submits table
   * @param result
   * @param submitId
   * @param testingId
   * @return
   */
  def finish(result: SolutionTestingResult, submitId: Long, testingId: Long): Future[Unit] = {
    client.run(
      (sqlu"update testings set finish_time = CURRENT_TIMESTAMP() where id = $testingId")
        .zip(submits.filter(_.id === submitId).map(x => (x.tested, x.taken, x.passed))
          .update(true, result.tests.size, result.tests.count(_._2.success)))).map(_ => ())
  }
}

case class TestingInfo(testingId: Int, problemId: String, state: Seq[(Int, Int)])


class DBReporter(val client: JdbcBackend#DatabaseDef) {
  import CPModel._
  import slick.jdbc.PostgresProfile.api._

  def allocateAndRegister(submit: SubmitObject, problemId: String): Future[Long] = {
    val allocTesting = (testings.map(x => (x.submit, x.problemURL)) returning testings.map(_.id)) += (submit.id, problemId)

    client.run(allocTesting.flatMap { testingID =>
      submits.filter(_.id === submit.id).map(_.testingID).update(testingID).map(_ => testingID)
    })
  }

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
