package org.stingray.contester.dispatcher

import java.nio.charset.StandardCharsets
import java.sql.Timestamp

import akka.actor.{Actor, Props, Stash}
import com.twitter.util.Future
import org.stingray.contester.common._
import org.stingray.contester.problems.{ProblemHandle, ProblemServerInterface}
import org.stingray.contester.testing.{SingleProgress, SolutionTester, SolutionTestingResult}
import slick.jdbc.{GetResult, JdbcBackend, JdbcType}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import com.github.nscala_time.time.Imports.DateTime
import play.api.Logging
import slick.ast.BaseTypedType
import slick.lifted.MappedTo

case class MoodleSubmit(id: Int, problemId: String, arrived: DateTime, sourceModule: Module, stdio: Boolean) extends Submit {
  val timestamp = arrived
  override val schoolMode = true

  override def toString =
    "MoodleSubmit(%d)".format(id)
}

/*
case class MoodleContesterLanguage(id: Long, name: String, ext: String, display: Option[Int])

case class MoodleTimestamp(underlying: Long) extends AnyVal with MappedTo[Long] {
  override def value: Long = underlying
}

object MoodleTimestamp {
  implicit val ordering: Ordering[MoodleTimestamp] = Ordering.by(_.underlying)
}

case class MoodleContesterSubmit(id: Long, contester: Long, student: Long, problem: Long, lang: Long,
                                 iomethod: Boolean, solution: Array[Byte], submitted: MoodleTimestamp, processed: Option[Int])

object MoodleMariadbSchema {
  import org.stingray.contester.dbmodel.MyPostgresProfile.api._

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
    def submitted = column[MoodleTimestamp]("submitted_uts")
    def processed = column[Option[Int]]("processed")

    override def * = (id, contester, student, problem, lang, iomethod, solution, submitted, processed) <> (
      MoodleContesterSubmit.tupled, MoodleContesterSubmit.unapply)
  }
}
 */

class MoodleSingleResult(client: JdbcBackend#DatabaseDef, val submit: MoodleSubmit, val testingId: Int) extends SingleProgress {
  import org.stingray.contester.dbmodel.MyPostgresProfile.api._

  def compile(r: CompileResult) =
    client.run(
      sqlu"""insert into mdl_contester_results (testingid, processed_uts, result, test, timex, memory, testeroutput, testererror)
            values ($testingId, extract(epoch from now()), ${r.status.value}, 0, ${r.time / 1000}, ${r.memory},
        ${new String(r.stdOut, StandardCharsets.UTF_8)},
        ${new String(r.stdErr, StandardCharsets.UTF_8)})""").map(_ => ())

  def test(id: Int, r: TestResult) =
    client.run(
      sqlu"""insert into mdl_contester_results (testingid, processed_uts, result, test, timex, memory, info, testeroutput,
             testererror, testerexitcode) values ($testingId, extract(epoch from now()), ${r.status.value}, $id, ${r.solution.time / 1000},
             ${r.solution.memory}, ${r.solution.returnCode.abs},
             ${new String(r.getTesterOutput, StandardCharsets.UTF_8)},
             ${new String(r.getTesterError, StandardCharsets.UTF_8)},
             ${r.getTesterReturnCode.abs})""").map(_ => ())

  def finish(r: SolutionTestingResult) = {
    val cval = if (r.compilation.success) 1 else 0
    val passed = r.tests.count(_._2.success)
    client.run(
      sqlu"""update mdl_contester_testings set finish_uts = extract(epoch from now()), compiled = ${cval}, taken = ${r.tests.size},
         passed = ${passed} where id = ${testingId}""".zip(
        sqlu"""update mdl_contester_submits set testing_id = ${testingId} where id = ${submit.id}""".transactionally)).map(_ => ())
  }
}

object MoodleTableScanner {
  case object Rescan
  case class UnprocessedEntries(items: Seq[Long])
  case class DoneWith(id: Long)

  def props(db: JdbcBackend#DatabaseDef, dispatcher: MoodleDispatcher) =
    Props(classOf[MoodleTableScanner], db, dispatcher)
}



class MoodleDispatcher(db: JdbcBackend#DatabaseDef, pdb: ProblemServerInterface, inv: SolutionTester, store: TestingStore) extends Logging {
  import org.stingray.contester.dbmodel.MyPostgresProfile.api._

  private[this] def getSubmit(id: Int) = {
    implicit val getMoodleSubmit = GetResult(r=>
      MoodleSubmit(r.nextInt(), r.nextInt().toString, new DateTime(r.nextLong() * 1000), new ByteBufferModule(r.nextString(), r.nextBytes()), r.nextBoolean())
    )

    val f = db.run(
      sql"""
         select
         s.id,
         s.problem,
         s.submitted_uts,
         l.ext,
         s.solution,
         s.iomethod
         from
         mdl_contester_submits s, mdl_contester_languages l
         where
         s.lang = l.id and
         s.id = ${id}
          """.as[MoodleSubmit].headOption)
    f.foreach(x => logger.info(s"${x}"))
    f
  }

  private[this] def start(client: JdbcBackend#DatabaseDef, submit: MoodleSubmit) =
    client.run(sql"""Insert into mdl_contester_testings (submitid, start_uts) values (${submit.id}, extract(epoch from now())) returning id""".as[Int].headOption)
      .map(v => new MoodleSingleResult(client, submit, v.get))

  private[this] def markWith(id: Int, value: Int) =
    db.run(sqlu"update mdl_contester_submits set processed = $value where id = $id")

  import org.stingray.contester.utils.Fu._

  def runId(id: Long): Future[Unit] = {
    val r = getSubmit(id.toInt).flatMap { submit =>
      submit.map(run)
        .getOrElse(Future.Done)
        .transform { v =>
          if (v.isThrow) {
            logger.error(s"$id: error $v")
          }
          val cv = if (v.isThrow) 254 else 255
          markWith(id.toInt, cv).unit
        }
    }
    r.failed.foreach{
      case e =>
        logger.error(s"$id: error $e", e)
    }
    r
  }

  private[this] def run(item: MoodleSubmit): Future[Unit] = {
    pdb.getMostRecentProblem(ProblemHandle(s"direct://school.sgu.ru/moodle/${item.problemId}")).flatMap { problem =>
      start(db, item).flatMap { reporter =>
        inv(item, item.sourceModule, problem.get, reporter, schoolMode = true,
          store.submit(item.id, reporter.testingId), Map.empty, stdio = item.stdio).flatMap(reporter.finish)
      }
    }
  }

}

class MoodleTableScanner(db: JdbcBackend#DatabaseDef, dispatcher: MoodleDispatcher) extends Actor with Stash with Logging {
  import MoodleTableScanner._
  import org.stingray.contester.dbmodel.MyPostgresProfile.api._

  private[this] def getUnprocessedEntries() =
    db.run(sql"select id from mdl_contester_submits where processed is null".as[Long])

  private[this] val active = mutable.Set[Long]()

  private[this] def startProcessing(id: Long) = {
    active.add(id)
    logger.info(s"Starting ${id}")
    val saved = self
    dispatcher.runId(id).ensure {
      logger.info(s"Done with ${id}")
      saved ! DoneWith(id)
    }
  }

  override def receive: Receive = {
    case Rescan =>
      getUnprocessedEntries()
            .onComplete { v =>
              if (v.isFailure) {
                logger.error(s"scan for entries: ${v}")
              }
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

  context.system.scheduler.scheduleWithFixedDelay(5 seconds, 5 seconds, self, Rescan)
}
