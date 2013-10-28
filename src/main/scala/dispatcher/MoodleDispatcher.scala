package org.stingray.contester.dispatcher

import org.stingray.contester.db.{ConnectionPool, SelectDispatcher}
import org.stingray.contester.common._
import java.sql.{ResultSet, Timestamp}
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.problems.{DirectProblemHandle, ProblemDb}
import org.stingray.contester.testing.{SolutionTester, SolutionTestingResult, SingleProgress}
import java.net.URL

case class MoodleSubmit(id: Int, problemId: String, arrived: Timestamp, sourceModule: Module) extends Submit {
  val timestamp = arrived
  override val schoolMode = true

  override def toString =
    "MoodleSubmit(%d)".format(id)
}

class MoodleSingleResult(client: ConnectionPool, val submit: MoodleSubmit, val testingId: Int) extends SingleProgress {
  def compile(r: CompileResult): Future[Unit] =
    client.execute(
      "insert into mdl_contester_results (testingid, processed, result, test, timex, memory, testeroutput, testererror) values (?, NOW(), ?, ?, ?, ?, ?, ?)",
      testingId, r.status, 0, r.time / 1000,
      r.memory, r.stdOut, r.stdErr).unit

  def test(id: Int, r: TestResult): Future[Unit] =
    client.execute(
      "Insert into mdl_contester_results (testingid, processed, result, test, timex, memory, info, testeroutput, testererror, testerexitcode) values (?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?)",
      testingId, r.status, id, r.solution.time / 1000,
      r.solution.memory, r.solution.returnCode,
      r.getTesterOutput, r.getTesterError,
      r.getTesterReturnCode).unit

  def finish(r: SolutionTestingResult): Future[Unit] =
    client.execute("update mdl_contester_testings set finish = NOW(), compiled = ?, taken = ?, passed = ? where ID = ?",
      if (r.compilation.success) "1" else "0", r.tests.size, r.tests.count(_._2.success), testingId).unit
}

class MoodleResultReporter(client: ConnectionPool, val submit: MoodleSubmit) {
  def start: Future[SingleProgress] =
    client.execute("Insert into mdl_contester_testings (submitid, start) values (?, NOW())", submit.id)
      .map(_.lastInsertId.get).map(new MoodleSingleResult(client, submit, _))
}

class MoodleDispatcher(db: ConnectionPool, pdb: ProblemDb, inv: SolutionTester, store: GridfsObjectStore) extends SelectDispatcher[MoodleSubmit](db) with Logging {
  def rowToSubmit(row: ResultSet): MoodleSubmit =
    MoodleSubmit(
      row.getInt("SubmitId"),
      row.getInt("ProblemId").toString,
      row.getTimestamp("Arrived"),
      new ByteBufferModule(row.getString("ModuleId"), row.getBytes("Solution"))
    )

  def selectAllNewQuery: String =
    """
      |select
      |mdl_contester_submits.id as SubmitId,
      |mdl_contester_languages.ext as ModuleId,
      |mdl_contester_submits.solution as Solution,
      |mdl_contester_submits.submitted as Arrived,
      |mdl_contester_submits.problem as ProblemId
      |from
      |mdl_contester_submits, mdl_contester_languages
      |where
      |mdl_contester_submits.lang = mdl_contester_languages.id and
      |mdl_contester_submits.processed is null
    """.stripMargin

  def selectAllActiveQuery: String =
    """
      |select
      |mdl_contester_submits.id as SubmitId,
      |mdl_contester_languages.ext as ModuleId,
      |mdl_contester_submits.solution as Solution,
      |mdl_contester_submits.submitted as Arrived,
      |mdl_contester_submits.problem as ProblemId
      |from
      |mdl_contester_submits, mdl_contester_languages
      |where
      |mdl_contester_submits.lang = mdl_contester_languages.id and
      |mdl_contester_submits.processed = 1
    """.stripMargin

  def grabOneQuery: String =
    "update mdl_contester_submits set processed = 1 where id = ?"

  def doneQuery: String =
    "update mdl_contester_submits set processed = 255 where id = ?"

  def failedQuery: String =
    "update mdl_contester_submits set processed = 254 where id = ?"

  def findUnfinishedTesting(item: MoodleSubmit) =
    db.select("select testingid from mdl_contester_testings where submitid = ? and finish is null", item.id) { row =>
      row.getInt("submitid")
    }.map(_.headOption)

  def startNewTesting(item: MoodleSubmit) =
    db.execute("Insert into mdl_contester_testings (submitid, start) values (?, NOW())", item.id)
      .map(_.lastInsertId.get)

  def run(item: MoodleSubmit): Future[Unit] = {
    trace("Received %s".format(item))
    pdb.getMostRecentProblem(new DirectProblemHandle(new URL("direct://school.sgu.ru/moodle/" + item.problemId))).flatMap { problem =>
      startNewTesting(item).flatMap { testingId =>
        val reporter = new MoodleSingleResult(db, item, testingId)
        inv(item, item.sourceModule, problem.get, reporter, true, store, new InstanceSubmitTestingHandle("school.sgu.ru/moodle", item.id, testingId), Map.empty).flatMap(reporter.finish)
      }
    }
  }
}
