package org.stingray.contester.dispatcher

import akka.actor.{Actor, Props, Stash}
import slick.jdbc.{GetResult, JdbcBackend}
import org.stingray.contester.common._
import java.sql.{ResultSet, Timestamp}

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.problems.{DirectProblemHandle, ProblemDb}
import org.stingray.contester.testing.{SingleProgress, SolutionTester, SolutionTestingResult}
import java.net.URI

import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable


case class MoodleSubmit(id: Int, problemId: String, arrived: Timestamp, sourceModule: Module) extends Submit {
  val timestamp = arrived
  override val schoolMode = true

  override def toString =
    "MoodleSubmit(%d)".format(id)
}

class MoodleSingleResult(client: JdbcBackend#DatabaseDef, val submit: MoodleSubmit, val testingId: Int) extends SingleProgress {
  import slick.driver.MySQLDriver.api._

  def compile(r: CompileResult) =
    client.run(
      sqlu"""insert into mdl_contester_results (testingid, processed, result, test, timex, memory, testeroutput, testererror)
            values ($testingId, NOW(), ${r.status.getNumber}, 0, ${r.time / 1000}, ${r.memory},
        ${new String(r.stdOut, "UTF-8")}, ${new String(r.stdErr, "UTF-8")})""").map(_ => ())

  def test(id: Int, r: TestResult) =
    client.run(
      sqlu"""Insert into mdl_contester_results (testingid, processed, result, test, timex, memory, info, testeroutput,
             testererror, testerexitcode) values ($testingId, NOW(), ${r.status.getNumber}, $id, ${r.solution.time / 1000},
             ${r.solution.memory}, ${r.solution.returnCode.abs},
             ${new String(r.getTesterOutput, "UTF-8")}, ${new String(r.getTesterError, "UTF-8")},
             ${r.getTesterReturnCode.abs})""").map(_ => ())

  def finish(r: SolutionTestingResult) = {
    val cval = if (r.compilation.success) "1" else "0"
    val passed = r.tests.count(_._2.success)
    client.run(
      sqlu"""update mdl_contester_testings set finish = NOW(), compiled = ${cval}, taken = ${r.tests.size},
         passed = ${passed} where ID = ${testingId}""").map(_ => ())
  }
}

object MoodleResultReporter {
  import slick.driver.MySQLDriver.api._

  def start(client: JdbcBackend#DatabaseDef, submit: MoodleSubmit) =
    client.run(sqlu"""Insert into mdl_contester_testings (submitid, start) values (${submit.id}, NOW())"""
      .andThen(sql"select LAST_INSERT_ID()".as[Int]).withPinnedSession)
      .map(_.head).map(new MoodleSingleResult(client, submit, _))
}

object MoodleTableScanner {
  case object Rescan
  case class UnprocessedEntries(items: Seq[Long])
  case class DoneWith(id: Long)

  def props(db: JdbcBackend#DatabaseDef, dispatcher: MoodleDispatcher) =
    Props(classOf[MoodleTableScanner], db, dispatcher)
}

class MoodleDispatcher(db: JdbcBackend#DatabaseDef, pdb: ProblemDb, inv: SolutionTester) extends Logging {
  import slick.driver.MySQLDriver.api._
  implicit val getMoodleSubmit = GetResult(r=>
    MoodleSubmit(r.nextInt(), r.nextInt().toString, r.nextTimestamp(), new ByteBufferModule(r.nextString(), r.nextBytes()))
  )

  private def getSubmit(id: Int) = {
    val f = db.run(
      sql"""
         select
         mdl_contester_submits.id as SubmitId,
         mdl_contester_submits.problem as ProblemId,
         mdl_contester_submits.submitted as Arrived,
         mdl_contester_languages.ext as ModuleId,
         mdl_contester_submits.solution as Solution
         from
         mdl_contester_submits, mdl_contester_languages
         where
         mdl_contester_submits.lang = mdl_contester_languages.id and
         mdl_contester_submits.id = ${id}
          """.as[MoodleSubmit]).map(_.headOption)
f.onFailure {
case x => info(s"$x")
}
f
}

  def markWith(id: Int, value: Int) =
    db.run(sqlu"update mdl_contester_submits set processed = $value where id = $id")


  import org.stingray.contester.utils.Fu._

  def runId(id: Long): Future[Unit] =
    getSubmit(id.toInt).flatMap { submit =>
      submit.map(run)
        .getOrElse(Future.Done)
          .transform { v =>
            val cv = if (v.isThrow) 254 else 255
            markWith(id.toInt, cv).unit
          }
    }

  def run(item: MoodleSubmit): Future[Unit] = {
    pdb.getMostRecentProblem(new DirectProblemHandle(new URI("direct://school.sgu.ru/moodle/" + item.problemId))).flatMap { problem =>
      MoodleResultReporter.start(db, item).flatMap { reporter =>
        inv(item, item.sourceModule, problem.get, reporter, true,
          new InstanceSubmitTestingHandle("school.sgu.ru/moodle", item.id, reporter.testingId), Map.empty).flatMap(reporter.finish)
      }
    }
  }

}

class MoodleTableScanner(db: JdbcBackend#DatabaseDef, dispatcher: MoodleDispatcher) extends Actor with Stash with Logging {
  import MoodleTableScanner._
  import slick.driver.MySQLDriver.api._

  private def getUnprocessedEntries() =
    db.run(sql"select id from mdl_contester_submits where processed is null".as[Long])

  val active = mutable.Set[Long]()

  private def startProcessing(id: Long) = {
    active.add(id)
    info(s"Starting ${id}")
    dispatcher.runId(id).ensure(self ! DoneWith(id))
  }

  override def receive: Receive = {
    case Rescan =>
      info("Rescan received")
      getUnprocessedEntries()
            .onComplete { v =>
              self ! UnprocessedEntries(v.getOrElse(Nil))
            }
      context.become(rescanning)
    case UnprocessedEntries(v) => ()
    case DoneWith(id) => active.remove(id)
  }

  def rescanning: Receive = {
    case UnprocessedEntries(v) =>
      info(s"Unprocessed(${v})")
      for(id <- v.filterNot(active(_))) {
        startProcessing(id)
      }
      context.unbecome()
    case Rescan =>
      info("Rescan ignored")
    case DoneWith(id) => stash()
  }

  import scala.concurrent.duration._

  context.system.scheduler.schedule(5 seconds, 5 seconds, self, Rescan)
}
