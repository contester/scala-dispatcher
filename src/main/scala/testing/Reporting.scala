package org.stingray.contester.testing

import org.stingray.contester.common.{TestResult, CompileResult}
import com.twitter.util.Future
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime
import org.stingray.contester.db.ConnectionPool
import org.stingray.contester.dispatcher.SubmitObject
import java.io.File
import org.apache.commons.io.FileUtils

case class SolutionTestingResult(compilation: CompileResult, tests: Seq[(Int, TestResult)])

trait SingleProgress {
  def compile(r: CompileResult): Future[Unit]
  def test(id: Int, r: TestResult): Future[Unit]

  def finish(r: SolutionTestingResult): Future[Unit]
  def rescue: PartialFunction[Throwable, Future[Unit]] = Map.empty
}

trait ProgressReporter {
  def start: Future[SingleProgress]

  final def apply(f: SingleProgress => Future[SolutionTestingResult]): Future[Unit] =
    start.flatMap { pr =>
      f(pr)
        .flatMap(pr.finish)
        .rescue(pr.rescue)
    }
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
      testingId, submit.id, r.status, 0, 0,
      0, r.stdOut, r.stdErr).unit.join(client.execute("Update Submits set Compiled = ? where ID = ?", (if (r.success) 1 else 0), submit.id))
      .unit

  def test(testId: Int, result: TestResult): Future[Unit] =
    client.execute("Insert into Results (UID, Submit, Result, Test, Timex, Memory, Info, TesterOutput, TesterError, TesterExitCode) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
      testingId, submit.id, result.status, testId, result.solution.time / 1000,
      result.solution.memory, result.solution.returnCode,
      result.getTesterOutput, result.getTesterError,
      result.getTesterReturnCode).unit

  def finish(result: SolutionTestingResult): Future[Unit] =
    client.execute("update Testings set Finish = NOW() where ID = ?", testingId).unit.join(
      client.execute("Update Submits set Finished = 1, Taken = ?, Passed = ? where ID = ?",
        result.tests.size, result.tests.filter(_._2.success).size, submit.id).unit).unit

}

class DBResultReporter(client: ConnectionPool, val submit: SubmitObject) extends ProgressReporter {
  def start =
    client.execute("Insert into Testings (Submit, Start) values (?, NOW())", submit.id)
      .map(_.lastInsertId.get)
      .flatMap { lastInsertId =>
      client.execute(
        "Replace Submits (Contest, Arrived, Team, Task, ID, Ext, Computer, TestingID, Touched, Finished) values (?, ?, ?, ?, ?, ?, ?, ?, NOW(), 0)",
        submit.contestId, submit.arrived, submit.teamId, submit.problemId, submit.id, submit.moduleType, submit.computer, lastInsertId)
        .unit.map(_ => new DBSingleResultReporter(client, submit, lastInsertId))
    }
}

class RawLogResultReporter(base: File, val submit: SubmitObject) extends ProgressReporter with SingleProgress {
  lazy val terse = new File(base, submit.id.toString)
  lazy val detailed = new File(base, submit.id.toString + ".proto")

  def rawlog(short: String, pb: Option[String] = None) =
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