package org.stingray.contester.dispatcher

import com.twitter.util.Future
import java.io.File
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.stingray.contester.SubmitObject
import org.stingray.contester.common._
import org.stingray.contester.db.ConnectionPool

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

class CombinedResultReporter(client: ConnectionPool, val submit: SubmitObject, base: File, val prefix: String, pdb: ProblemDb, doneCb: Function[Int, Unit]) extends TestingResultReporter {
  val tests = collection.mutable.Map[Int, Boolean]()
  lazy val terse = new File(base, submit.id.toString)
  lazy val detailed = new File(base, submit.id.toString + ".proto")

  lazy val getId = client.execute("Insert into Testings (Submit, Start) values (?, NOW())", submit.id)
    .map(_.lastInsertId.get)

  def rawlog(short: String, pb: Option[String] = None) =
    Future {
      import collection.JavaConversions._
      val ts = CombinedResultReporter.ts
      FileUtils.writeStringToFile(terse, ts + " " + short + "\n", true)
      FileUtils.writeStringToFile(detailed, ts + " " + short + "\n", true)
      pb.foreach(p => FileUtils.writeLines(detailed, p.toString.lines.map(ts + "     " + _).toIterable, true))
    }

  lazy val start =
    rawlog("Started testing " + submit).join(
    getId.flatMap { lastInsertId =>
      Future {
      }.join(client.execute("Replace Submits (Contest, Arrived, Team, Task, ID, Ext, Computer, TestingID, Touched, Finished) values (?, ?, ?, ?, ?, ?, ?, ?, NOW(), 0)",
        submit.contestId, submit.arrived, submit.teamId, submit.problemId, submit.id, submit.moduleType, submit.computer, lastInsertId))
    }.unit).unit

  def report(r: Result): Future[Unit] = r match {
    case c: CompileResult => compileResult(c)
    case t: TestResult => testResult(t)
  }

  def compileResult(result: CompileResult) =
    start.flatMap { _ =>
    rawlog("  " + result, Some(result.toMap.toString())).join(
    getId.flatMap { testingId =>
      client.execute("insert into Results (UID, Submit, Result, Test, Timex, Memory, TesterOutput, TesterError) values (?, ?, ?, ?, ?, ?, ?, ?)",
      testingId, submit.id, result.status, 0, 0,
      0, result.stdOut, result.stdErr).unit
    }).join(
      start.flatMap { unit =>
        client.execute("Update Submits set Compiled = ? where ID = ?", (if (result.success) 1 else 0), submit.id)
      }).unit
    }

  def testResult(result: TestResult) = {
    val testId = result.testId
    tests(testId) = result.success
    rawlog("  " + result, Some(result.toMap.toString())).join(
    getId.flatMap { testingId =>
      client.execute("Insert into Results (UID, Submit, Result, Test, Timex, Memory, Info, TesterOutput, TesterError, TesterExitCode) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
      testingId, submit.id, result.status, testId, result.solution.time / 1000,
      result.solution.memory, result.solution.returnCode,
      result.getTesterOutput, result.getTesterError,
      result.getTesterReturnCode)
    }.unit).unit
  }

  def getTestTaken = tests.keys.size
  def getTestsPassed = tests.values.count(y => y)

  // TODO: fix implicit race WTF IS THIS
  def finish(isError: Boolean): Future[Unit] =
    getId.flatMap { testingId =>
      client.execute("update Testings set Finish = NOW() where ID = ?", testingId)
    }.join(
    start.flatMap { x =>
      if (isError)
        rawlog("NO UPDATE Finished testing " + submit)
      else
        rawlog("Finished testing " + submit).join(
          client.execute("Update Submits set Finished = 1, Taken = ?, Passed = ? where ID = ?",
          getTestTaken, getTestsPassed, submit.id)).unit.map(_ => doneCb(submit.id))
    }).unit

}
