package org.stingray.contester.dispatcher

import java.nio.charset.StandardCharsets
import java.sql.Timestamp

import akka.actor.{Actor, Props, Stash}
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common._
import org.stingray.contester.problems.{ProblemHandle, ProblemServerInterface}
import org.stingray.contester.testing.{SingleProgress, SolutionTester, SolutionTestingResult}
import slick.jdbc.{GetResult, JdbcBackend, JdbcType}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import com.github.nscala_time.time.Imports.DateTime
import slick.ast.BaseTypedType

case class MoodleSubmit(id: Int, problemId: String, arrived: DateTime, sourceModule: Module, stdio: Boolean) extends Submit {
  val timestamp = arrived
  override val schoolMode = true

  override def toString =
    "MoodleSubmit(%d)".format(id)
}

case class MoodleContesterLanguage(id: Long, name: String, ext: String, display: Option[Int])

case class MoodleContesterSubmit(id: Long, contester: Long, student: Long, problem: Long, lang: Long,
                                 iomethod: Boolean, solution: Array[Byte], submitted: DateTime, processed: Option[Int])

object MoodleMariadbSchema {
  import slick.jdbc.MySQLProfile.api._

  implicit val datetimeColumnType
  : JdbcType[DateTime] with BaseTypedType[DateTime] =
    MappedColumnType.base[DateTime, Timestamp](
      x => new Timestamp(x.getMillis),
      x => new DateTime(x)
    )

  class MoodleContesterLanguages(tag: Tag) extends Table[MoodleContesterLanguage](tag, "mdl_contester_languages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def ext= column[String]("ext")
    def display = column[Option[Int]]("display")

    override def * = (id, name, ext, display) <> (MoodleContesterLanguage.tupled, MoodleContesterLanguage.unapply)
  }

  val contesterLanguages = TableQuery[MoodleContesterLanguages]

  class MoodleContesterSubmits(tag: Tag) extends Table[MoodleContesterSubmit](tag, "mdl_contester_submits") {
    def id= column[Long]("id", O.PrimaryKey, O.AutoInc)
    def contester = column[Long]("contester")
    def student = column[Long]("student")
    def problem = column[Long]("problem")
    def lang = column[Long]("lang")
    def iomethod = column[Boolean]("iomethod")
    def solution = column[Array[Byte]]("solution")
    def submitted = column[DateTime]("submitted")
    def processed = column[Option[Int]]("processed")

    override def * = (id, contester, student, problem, lang, iomethod, solution, submitted, processed) <> (
      MoodleContesterSubmit.tupled, MoodleContesterSubmit.unapply)
  }
}

class MoodleSingleResult(client: JdbcBackend#DatabaseDef, val submit: MoodleSubmit, val testingId: Int) extends SingleProgress {
  import slick.jdbc.MySQLProfile.api._

  def compile(r: CompileResult) =
    client.run(
      sqlu"""insert into mdl_contester_results (testingid, processed, processed_uts, result, test, timex, memory, testeroutput, testererror)
            values ($testingId, NOW(), UNIX_TIMESTAMP(), ${r.status.value}, 0, ${r.time / 1000}, ${r.memory},
        ${new String(r.stdOut, StandardCharsets.UTF_8)},
        ${new String(r.stdErr, StandardCharsets.UTF_8)})""").map(_ => ())

  def test(id: Int, r: TestResult) =
    client.run(
      sqlu"""Insert into mdl_contester_results (testingid, processed, processed_uts, result, test, timex, memory, info, testeroutput,
             testererror, testerexitcode) values ($testingId, NOW(), UNIX_TIMESTAMP(), ${r.status.value}, $id, ${r.solution.time / 1000},
             ${r.solution.memory}, ${r.solution.returnCode.abs},
             ${new String(r.getTesterOutput, StandardCharsets.UTF_8)},
             ${new String(r.getTesterError, StandardCharsets.UTF_8)},
             ${r.getTesterReturnCode.abs})""").map(_ => ())

  def finish(r: SolutionTestingResult) = {
    val cval = if (r.compilation.success) "1" else "0"
    val passed = r.tests.count(_._2.success)
    client.run(
      sqlu"""update mdl_contester_testings set finish = NOW(), finish_uts = UNIX_TIMESTAMP(), compiled = ${cval}, taken = ${r.tests.size},
         passed = ${passed} where ID = ${testingId}""").map(_ => ())
  }
}

object MoodleResultReporter {
  import slick.jdbc.MySQLProfile.api._

  def start(client: JdbcBackend#DatabaseDef, submit: MoodleSubmit) =
    client.run(sqlu"""Insert into mdl_contester_testings (submitid, start, start_uts) values (${submit.id}, NOW(), UNIX_TIMESTAMP())"""
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

class MoodleDispatcher(db: JdbcBackend#DatabaseDef, pdb: ProblemServerInterface, inv: SolutionTester, store: TestingStore) extends Logging {
  import slick.jdbc.MySQLProfile.api._
  implicit val getMoodleSubmit = GetResult(r=>
    MoodleSubmit(r.nextInt(), r.nextInt().toString, new DateTime(r.nextLong() * 1000), new ByteBufferModule(r.nextString(), r.nextBytes()), r.nextBoolean())
  )

  private def getSubmit(id: Int) = {
    val f = db.run(
      sql"""
         select
         mdl_contester_submits.id as SubmitId,
         mdl_contester_submits.problem as ProblemId,
         mdl_contester_submits.submitted_uts as Arrived,
         mdl_contester_languages.ext as ModuleId,
         mdl_contester_submits.solution as Solution,
         mdl_contester_submits.iomethod as StdioMethod
         from
         mdl_contester_submits, mdl_contester_languages
         where
         mdl_contester_submits.lang = mdl_contester_languages.id and
         mdl_contester_submits.id = ${id}
          """.as[MoodleSubmit]).map(_.headOption)
    f.foreach(x => info(s"${x}"))
    f
  }

  def markWith(id: Int, value: Int) =
    db.run(sqlu"update mdl_contester_submits set processed = $value where id = $id")

  import org.stingray.contester.utils.Fu._

  def runId(id: Long): Future[Unit] = {
    val r = getSubmit(id.toInt).flatMap { submit =>
      submit.map(run)
        .getOrElse(Future.Done)
        .transform { v =>
          val cv = if (v.isThrow) 254 else 255
          markWith(id.toInt, cv).unit
        }
    }
    r.failed.foreach{
      case e =>
        error(s"err: $e")
    }
    r
  }

  def run(item: MoodleSubmit): Future[Unit] = {
    pdb.getMostRecentProblem(ProblemHandle(s"direct://school.sgu.ru/moodle/${item.problemId}")).flatMap { problem =>
      MoodleResultReporter.start(db, item).flatMap { reporter =>
        inv(item, item.sourceModule, problem.get, reporter, schoolMode = true,
          store.submit(item.id, reporter.testingId), Map.empty, stdio = item.stdio).flatMap(reporter.finish)
      }
    }
  }

}

class MoodleTableScanner(db: JdbcBackend#DatabaseDef, dispatcher: MoodleDispatcher) extends Actor with Stash with Logging {
  import MoodleTableScanner._
  import slick.jdbc.MySQLProfile.api._

  private def getUnprocessedEntries() =
    db.run(sql"select id from mdl_contester_submits where processed is null".as[Long])

  val active = mutable.Set[Long]()

  private def startProcessing(id: Long) = {
    active.add(id)
    info(s"Starting ${id}")
    val saved = self
    dispatcher.runId(id).ensure {
      info(s"Done with ${id}")
      saved ! DoneWith(id)
    }
  }

  override def receive: Receive = {
    case Rescan =>
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
      for(id <- v.filterNot(active(_))) {
        startProcessing(id)
      }
      context.unbecome()
    case Rescan => ()
    case DoneWith(id) => stash()
  }

  import scala.concurrent.duration._

  context.system.scheduler.schedule(5 seconds, 5 seconds, self, Rescan)
}
