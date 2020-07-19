package org.stingray.contester.dispatcher

import akka.actor.Actor
import com.twitter.util.Future
import org.stingray.contester.polygon._
import play.api.Logging
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext.Implicits.global

case class Contest(id: Int, name: String, polygonId: PolygonContestId, Language: String)
case class Problem(contest: Int, id: String, tests: Int, name: String)
case class Language(id: Int, name: String, moduleID: String)

class ContestNotFoundException(id: Int) extends Throwable(id.toString)

object CPModel {
  import com.github.nscala_time.time.Imports._
  import com.github.tototoshi.slick.PostgresJodaSupport._
  import slick.jdbc.PostgresProfile.api._

  case class Contests(tag: Tag) extends Table[(Int, String, DateTime, Option[DateTime], DateTime, DateTime, String, String)](tag, "contests") {
    def id = column[Int]("id")
    def name = column[String]("name")
    def startTime = column[DateTime]("start_time")
    def freezeTime = column[Option[DateTime]]("freeze_time")
    def endTime = column[DateTime]("end_time")
    def exposeTime = column[DateTime]("expose_time")
    def polygonId = column[String]("polygon_id")
    def language = column[String]("language")

    def * = (id, name, startTime, freezeTime, endTime, exposeTime, polygonId, language)
  }

  val contests = TableQuery[Contests]

  case class Problems(tag: Tag) extends Table[Problem](tag, "problems") {
    def contestID = column[Int]("contest_id")
    def id = column[String]("id")
    def tests = column[Int]("tests")
    def name = column[String]("name")

    def * = (contestID, id, tests, name) <> (Problem.tupled, Problem.unapply)
  }

  val problems = TableQuery[Problems]

  case class Languages(tag: Tag) extends Table[Language](tag, "languages") {
    def id = column[Int]("id")
    def name = column[String]("name")
    def moduleID = column[String]("module_id")

    def * = (id, name, moduleID) <> (Language.tupled, Language.unapply)
  }

  val languages = TableQuery[Languages]

  case class Submits(tag: Tag) extends Table[(Long, Int, Int, String, Int, Array[Byte], DateTime, Int, Boolean, Boolean, Int, Long, Int, Boolean)](tag, "submits") {
    def id = column[Long]("id", O.AutoInc, O.PrimaryKey)
    def contest = column[Int]("contest")
    def team = column[Int]("team_id")
    def problem = column[String]("problem")
    def language = column[Int]("language_id")
    def source = column[Array[Byte]]("source")
    def arrived = column[DateTime]("submit_time_absolute")
    def arrivedSeconds = column[Int]("submit_time_relative_seconds")
    def tested = column[Boolean]("tested")
    def success = column[Boolean]("success")
    def passed = column[Int]("passed")
    def testingID = column[Long]("testing_id")
    def taken = column[Int]("taken")
    def compiled = column[Boolean]("compiled")

    override def * = (id, contest, team, problem, language, source, arrived, arrivedSeconds, tested, success, passed, testingID, taken, compiled)
  }

  val submits = TableQuery[Submits]

  def getSubmitByID(id: Long) =
    for {
      submit <- submits if submit.id === id
      lang <- languages if lang.id === submit.language
      contest <- contests if contest.id === submit.contest && contest.polygonId =!= ""
    } yield (submit.id, submit.contest, submit.team, submit.problem, submit.arrived, lang.moduleID, submit.source, contest.polygonId)

  case class Testings(tag: Tag) extends Table[(Long, Long, DateTime, String, Option[DateTime])](tag, "testings") {
    def id = column[Long]("id", O.AutoInc, O.PrimaryKey)
    def submit = column[Long]("submit")
    def startTime = column[DateTime]("start_time")
    def problemURL = column[String]("problem_id")
    def finishTime = column[Option[DateTime]]("finish_time")

    override def * = (id, submit, startTime, problemURL, finishTime)
  }

  val testings = TableQuery[Testings]

  case class Results(tag: Tag) extends Table[(Long, Long, Int, DateTime, Long, Long, Long, Array[Byte], Array[Byte], Long)](tag, "results") {
    def testingID = column[Long]("testing_id")
    def testID = column[Long]("test_id")
    def resultCode = column[Int]("result_code")
    def recordTime = column[DateTime]("record_time")
    def timeMs = column[Long]("time_ms")
    def memoryBytes = column[Long]("memory_bytes")
    def returnCode = column[Long]("return_code")
    def testerOutput = column[Array[Byte]]("tester_output")
    def testerError = column[Array[Byte]]("tester_error")
    def testerReturnCode = column[Long]("tester_return_code")

    override def * = (testingID, testID, resultCode, recordTime, timeMs, memoryBytes, returnCode, testerOutput, testerError, testerReturnCode)
  }

  val results = TableQuery[Results]

  case class CustomTests(tag: Tag) extends Table[(Long, Int, Int, Int, Array[Byte], Array[Byte], Array[Byte], DateTime,
    Option[DateTime], Int, Long, Long, Long)](tag, "custom_test") {
    def id = column[Long]("id", O.AutoInc, O.PrimaryKey)
    def contest = column[Int]("contest")
    def team = column[Int]("team_id")
    def language = column[Int]("language_id")
    def source = column[Array[Byte]]("source")
    def input = column[Array[Byte]]("input")
    def output = column[Array[Byte]]("output")
    def arrived = column[DateTime]("submit_time_absolute")
    def finishTime = column[Option[DateTime]]("finish_time")
    def resultCode = column[Int]("result_code")
    def timeMs = column[Long]("time_ms")
    def memoryBytes = column[Long]("memory_bytes")
    def returnCode = column[Long]("return_code")

    override def * = (id, contest, team, language, source, input, output, arrived, finishTime, resultCode, timeMs, memoryBytes, returnCode)
  }

  val customTests = TableQuery[CustomTests]

  def getCustomTestByID(id: Long) =
    for {
      c <- customTests if c.id === id
      lang <- languages if lang.id === c.language
    } yield (c.id, c.contest, c.team, c.arrived, lang.moduleID, c.source, c.input)

}

object ContestTableScanner {
  case object Rescan

  case class ContestMap(map: Map[Contest, ContestWithProblems])

  case class GetContest(id: Int)
}

class ContestTableScanner(db: JdbcBackend#DatabaseDef, resolver: PolygonClient)
  extends Actor with Logging {
  import ContestTableScanner._

  private[this] def getContestsFromDb = {
    import CPModel._
    import slick.jdbc.PostgresProfile.api._

    db.run(contests.filter(_.polygonId =!= "").map(x => (x.id, x.name, x.polygonId, x.language)).result).map(_.map(x =>
      Contest(x._1, x._2, PolygonContestId(x._3), x._4)
    ))
  }

  private[this] def maybeUpdateContestName(contestId: Int, rowName: String, contestName: String) = {
    import CPModel._
    import slick.jdbc.PostgresProfile.api._
    if (rowName != contestName)
      Some(contests.filter(_.id === contestId).map(_.name).update(contestName))
    else
      None
  }

  import org.stingray.contester.utils.Fu._

  private def getNewContestMap =
    getContestsFromDb.flatMap { contests =>
      logger.trace(s"received $contests")

      Future.collect(contests.map(x => resolver.getContest(x.polygonId).map(p => x -> p))).map(_.toMap)
    }

  private def updateContest(row: Contest, contest: ContestWithProblems) = {
    val nameChange = maybeUpdateContestName(row.id, row.name, contest.contest.getName(row.Language))
    import CPModel._
    import slick.jdbc.PostgresProfile.api._

    val pfixes = problems.filter(x => x.contestID === row.id).result.flatMap { probs =>
      val problemMap = probs.filter(_.contest == row.id).map(x => x.id.toUpperCase -> x).toMap

      val deletes = (problemMap.keySet -- contest.problems.keySet).toSeq.map { problemId =>
        problems.filter(x => x.contestID === row.id && x.id === problemId).delete
      }

      val updates = contest.problems.map(x => x -> problemMap.get(x._1)).collect {
        case ((problemId, polygonProblem), Some(problemRow))
          if (problemRow.name != polygonProblem.getTitle(row.Language) || problemRow.tests != polygonProblem.testCount) =>
          val problemTitle = polygonProblem.getTitle(row.Language)
          logger.trace(s"$problemRow | ${problemTitle} | ${polygonProblem.testCount}")
          logger.trace(s"replacing problem $problemId := $polygonProblem")

          problems.insertOrUpdate(Problem(row.id, problemId, polygonProblem.testCount, problemTitle))

        case (((problemId, polygonProblem), None)) =>
          val problemTitle = polygonProblem.getTitle(row.Language)
          logger.trace(s"adding problem $problemId := $polygonProblem")
          problems.insertOrUpdate(Problem(row.id, problemId, polygonProblem.testCount, problemTitle))
      }
      DBIO.sequence(deletes ++ updates)
    }
    DBIO.seq(DBIO.sequenceOption(nameChange), pfixes)
  }

  private def updateContests(m: Map[Contest, ContestWithProblems]) = {
    import slick.jdbc.PostgresProfile.api._
    db.run(DBIO.sequence(m.map(x => updateContest(x._1, x._2)).toSeq))
  }

  import scala.concurrent.duration._

  override def receive = {
    case ContestMap(map) =>
      logger.trace("Contest map received: $map")

    case Rescan =>
      logger.trace("Starting contest rescan")
      val cm = getNewContestMap
      cm.foreach { newMap =>
        logger.trace(s"Contest rescan done, $newMap")
        self ! ContestMap(newMap)
        val f = updateContests(newMap)
          f.onComplete { _ =>
          logger.trace("Scheduling next rescan")
          context.system.scheduler.scheduleOnce(60 seconds, self, Rescan)
        }
        f.failed.foreach(e => logger.error("rescan failed", e))
      }
      cm.failed.foreach(e => logger.error("rerescan failed", e))
  }

  context.system.scheduler.scheduleOnce(0 seconds, self, Rescan)
}
