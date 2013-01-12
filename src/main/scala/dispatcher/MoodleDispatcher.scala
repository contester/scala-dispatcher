package org.stingray.contester.dispatcher

import org.stingray.contester.invokers.TimeKey
import org.stingray.contester.db.{ConnectionPool, SelectDispatcher, HasId}
import org.stingray.contester.common._
import java.sql.{ResultSet, Timestamp}
import com.twitter.util.Future
import org.stingray.contester.problems.ProblemDb

case class MoodleSubmit(id: Int, problemId: String, moduleType: String, arrived: Timestamp, source: Array[Byte]) extends TimeKey with HasId with SubmitWithModule {
  val timestamp = arrived

  override def toString =
    "MoodleSubmit(%d)".format(id)
}

class MoodleResultReporter(client: ConnectionPool, val submit: MoodleSubmit) extends TestingResultReporter {
  val tests = collection.mutable.Map[Int, Boolean]()
  var compiled = false

  lazy val getId = client.execute("Insert into mdl_contester_testings (submitid, start) values (?, NOW())", submit.id)
    .map(_.lastInsertId.get)

  lazy val start =
    getId.unit

  def report(r: Result): Future[Unit] = r match {
    case c: CompileResult => compileResult(c)
    case t: TestResult => testResult(t)
  }

  def compileResult(result: CompileResult) =
    getId.flatMap { testingId =>
      compiled = result.success
      client.execute(
        "insert into mdl_contester_results (testingid, processed, result, test, timex, memory, testeroutput, testererror) values (?, NOW(), ?, ?, ?, ?, ?, ?)",
        testingId, result.status, 0, result.time / 1000,
        result.memory, result.stdOut, result.stdErr).unit
    }

  def testResult(result: TestResult) = {
    val testId = result.testId
    tests(testId) = result.success
    getId.flatMap { testingId =>
        client.execute(
          "Insert into mdl_contester_results (testingid, processed, result, test, timex, memory, info, testeroutput, testererror, testerexitcode) values (?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?)",
          testingId, result.status, testId, result.solution.time / 1000,
          result.solution.memory, result.solution.returnCode,
          result.getTesterOutput, result.getTesterError,
          result.getTesterReturnCode)
    }.unit
  }

  def getTestTaken = tests.keys.size
  def getTestsPassed = tests.values.count(y => y)

  // TODO: fix implicit race WTF IS THIS
  def finish(isError: Boolean): Future[Unit] =
    getId.flatMap { testingId =>
      client.execute("update mdl_contester_testings set finish = NOW(), compiled = ?, taken = ?, passed = ? where ID = ?",
        if (compiled) 1 else 0, getTestTaken, getTestsPassed, testingId)
    }.unit
}


class MoodleDispatcher(db: ConnectionPool, pdb: ProblemDb) extends SelectDispatcher[MoodleSubmit](db) {
  def rowToSubmit(row: ResultSet): MoodleSubmit = null

  def selectAllActiveQuery: String =
    """
      |select
      |mdl_contester_submits.id as SubmitId,
      |mdl_contester_languages.ext as ModuleId,
      |mdl_contester_submits.solution as Solution,
      |mdl_contester_submits.submitted as Arrived,
      |mdl_contester_submit.problem as ProblemId
      |from
      |mdl_contester_submits, mdl_contester_languages,
      |where
      |mdl_contester_submits.lang = mdl_contester_languages.id and
      |mdl_contester_submits.processed = 1
    """.stripMargin

  def grabAllQuery: String =
    "update mdl_contester_submits set processed = 1 where processed is NULL"

  def grabOneQuery: String =
    "update mdl_contester_submits set processed = 1 where id = ?"

  def doneQuery: String =
    "update mdl_contester_submits set processed = 255 where id = ?"

  def failedQuery: String =
    "update mdl_contester_submits set processed = 254 where id = ?"

  def run(item: MoodleSubmit): Future[Unit] = null
}