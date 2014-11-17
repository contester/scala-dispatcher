package org.stingray.contester.testing

import org.stingray.contester.common.{Result, TestResult, CompileResult}
import com.twitter.util.Future
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime
import org.stingray.contester.db.ConnectionPool
import org.stingray.contester.dispatcher.SubmitObject
import java.io.File
import org.apache.commons.io.FileUtils
import org.stingray.contester.engine.CustomTestResult
import java.sql.ResultSet

case class SolutionTestingResult(compilation: CompileResult, tests: Seq[(Int, Result)])
case class CustomTestingResult(compilation: CompileResult, test: Option[CustomTestResult])

trait SingleProgress {
  def compile(r: CompileResult): Future[Unit]
  def test(id: Int, r: TestResult): Future[Unit]
}

object NullReporter extends SingleProgress {
  def compile(r: CompileResult): Future[Unit] = Future.Done

  def test(id: Int, r: TestResult): Future[Unit] = Future.Done

  def finish(r: SolutionTestingResult): Future[Unit] = Future.Done
}

class CombinedSingleProgress(val db: DBSingleResultReporter, val raw: RawLogResultReporter) extends SingleProgress {
  def compile(r: CompileResult): Future[Unit] = db.compile(r).join(raw.compile(r)).unit

  def test(id: Int, r: TestResult): Future[Unit] = db.test(id, r).join(raw.test(id, r)).unit
}

object CombinedResultReporter {
  val fmt = ISODateTimeFormat.dateTime()

  def ts =
    "[" + fmt.print(new DateTime()) + "]"

  private def toSeqs[A, B](data: Map[A, B]): (Iterable[A], Iterable[B]) = {
    val keys = data.keys
    (keys, keys.map(data))
  }

  private def asInsertPart(data: Map[String, Any]): (String, Seq[Any]) =
    toSeqs(data) match {
      case (keys, values) =>
        ("(%s) values (%s)".format(keys.mkString(", "), keys.map(_ => "?").mkString(", ")), values.toSeq)
    }
}

class DBSingleResultReporter(client: ConnectionPool, val submit: SubmitObject, val testingId: Int) extends SingleProgress {
  def compile(r: CompileResult): Future[Unit] =
    client.execute("insert into Results (UID, Submit, Result, Test, Timex, Memory, TesterOutput, TesterError) values (?, ?, ?, ?, ?, ?, ?, ?)",
      testingId, submit.id, r.status.getNumber, 0, 0,
      0, new String(r.stdOut, "cp866"), new String(r.stdErr, "cp866")).unit.join(client.execute("Update Submits set Compiled = ? where ID = ?", if (r
        .success) 1
    else 0, submit.id))
      .unit

  def test(testId: Int, result: TestResult): Future[Unit] =
    client.execute("Insert into Results (UID, Submit, Result, Test, Timex, Memory, Info, TesterOutput, TesterError, TesterExitCode) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
      testingId, submit.id, result.status.getNumber, testId, result.solution.time / 1000,
      result.solution.memory, result.solution.returnCode,
      result.getTesterOutput, new String(result.getTesterError, "windows-1251"),
      result.getTesterReturnCode).unit

  private def finishTesting(testingId: Int) =
    client.execute("update Testings set Finish = NOW() where ID = ?", testingId)

  private def finishSubmit(submitId: Int, result: SolutionTestingResult) =
    client.execute("Update Submits set Finished = 1, Taken = ?, Passed = ? where ID = ?",
      result.tests.size, result.tests.count(_._2.success), submitId)

  /**
   * Close the testing and update submits table
   * @param result
   * @param submitId
   * @param testingId
   * @return
   */
  def finish(result: SolutionTestingResult, submitId: Int, testingId: Int): Future[Unit] =
    finishTesting(testingId).join(finishSubmit(submitId, result)).unit
}

class TestingInfo(val testingId: Int, val problemId: String, val state: Seq[(Int, Int)])

class DBReporter(val client: ConnectionPool) {
  /**
   * Allocate new testing ID in the database.
   * @param submitId Submit ID.
   * @param problemId Problem ID/Url.
   * @return Future testing ID.
   */
  def allocateTesting(submitId: Int, problemId: String): Future[Int] =
    client.execute("Insert into Testings (Submit, ProblemID, Start) values (?, ?, NOW())", submitId, problemId)
        .map(_.lastInsertId.get)

  /**
   * Update Submits table for given submit with given testing ID.
   * @param submit Submit object to consider.
   * @param testingId Testing ID to update with.
   * @return
   */
  def registerTestingOnly(submit: SubmitObject, testingId: Int) =
    client.execute(
      "Replace Submits (Contest, Arrived, Team, Task, ID, Ext, Computer, TestingID, Touched, Finished) values (?, ?, ?, ?, ?, ?, ?, ?, NOW(), 0)",
      submit.contestId, submit.arrived, submit.teamId, submit.problemId, submit.id, submit.sourceModule.moduleType, submit.computer, testingId)

  private def testingRow(row: ResultSet): (Int, String) =
    (row.getInt("ID"), row.getString("ProblemID"))

  // Get testing ID from submit row, or None
  private def getTestingIdFromSubmit(submitId: Int): Future[Option[Int]] =
    client.select("select TestingID from Submits where Id = ?", submitId) { row =>
      Option(row.getInt("TestingID"))
    }.map(_.headOption.flatten)

  // Get active testing from testingId, or None
  private def getTestingFromSubmitAndId(submitId: Int, testingId: Int): Future[Option[(Int, String)]] =
    client.select(
      "select ID, ProblemID from Testings where Finish is null and ProblemID is not null and Submit = ? and ID = ?",
      submitId, testingId)(testingRow).map(_.headOption)

  // Get most recent active testing
  private def getTestingFromSubmit(submitId: Int): Future[Option[(Int, String)]] =
    client.select(
      "select ID, ProblemID from Testings where Finish is null and ProblemID is not null and Submit = ? order by ID desc limit 1",
      submitId)(testingRow).map(_.headOption)

  private def getAnyTesting(submitId: Int): Future[Option[(Int, String)]] =
    getTestingIdFromSubmit(submitId).flatMap { optTestingId =>
      optTestingId.map(getTestingFromSubmitAndId(submitId, _)).getOrElse(Future.None)
    }.flatMap { optTesting =>
        if (optTesting.isEmpty)
          getTestingFromSubmit(submitId)
        else
          Future.value(optTesting)
    }

  private def getTestingState(testingId: Int): Future[Seq[(Int, Int)]] =
    client.select("select Test, Result from Results where UID = ? and Test > 0", testingId) { row =>
      (row.getInt("Test"), row.getInt("Result"))
    }

  def getAnyTestingAndState(submitId: Int): Future[Option[TestingInfo]] =
    getAnyTesting(submitId).flatMap(_.map { testing =>
      getTestingState(testing._1).map(x => Some(new TestingInfo(testing._1, testing._2, x)))
    }.getOrElse(Future.None))
}

class RawLogResultReporter(base: File, val submit: SubmitObject) extends SingleProgress {
  lazy val terse = new File(base, submit.id.toString)
  lazy val detailed = new File(base, submit.id.toString + ".proto")

  private def rawlog(short: String, pb: Option[String] = None) =
    Future {
      import collection.JavaConversions._
      val ts = CombinedResultReporter.ts
      FileUtils.writeStringToFile(terse, ts + " " + short + "\n", true)
      FileUtils.writeStringToFile(detailed, ts + " " + short + "\n", true)
      pb.foreach(p => FileUtils.writeLines(detailed, p.toString.lines.map(ts + "     " + _).toIterable, true))
    }

  def compile(result: CompileResult): Future[Unit] =
    rawlog("  " + result, Some(result.toMap.toString()))

  def test(id: Int, result: TestResult): Future[Unit] =
    rawlog("  Test " + id + ": " + result, Some(result.toMap.toString()))

  def finish(result: SolutionTestingResult): Future[Unit] =
    rawlog("Finished testing " + submit)

  def start =
    rawlog("Started testing " + submit).map(_ => this)
}
