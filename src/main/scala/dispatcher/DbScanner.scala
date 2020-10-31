package org.stingray.contester.dispatcher

import akka.actor.Actor
import com.twitter.util.Future
import org.stingray.contester.dbmodel.{SlickModel, Problem}
import org.stingray.contester.polygon._
import play.api.Logging
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext.Implicits.global

case class Contest(id: Int, name: String, polygonId: PolygonContestId, Language: String)

object CPModel {
  import org.stingray.contester.dbmodel.MyPostgresProfile.api._

  val contestsWithPolygonID = SlickModel.contests.filter(_.polygonId =!= "").map(x => (x.id, x.name, x.polygonId, x.language))

  private[this] def getContestNameByID(id: Rep[Int]) =
    SlickModel.contests.filter(_.id === id).map(_.name)

  val contestNameByID = Compiled(getContestNameByID _)

  private[this] def getSubmitTestedByID(id: Rep[Long]) =
    SlickModel.submits.filter(_.id === id).map(_.tested)

  val submitTestedByID = Compiled(getSubmitTestedByID _)

  private[this] def getSubmitCompiledByID(id: Rep[Long]) =
    SlickModel.submits.filter(_.id === id).map(x => (x.testingID, x.compiled))

  val submitCompiledByID = Compiled(getSubmitCompiledByID _)

  def getSubmitByID(id: Long) =
    for {
      submit <- SlickModel.submits if submit.id === id
      lang <- SlickModel.compilers if lang.id === submit.language
      contest <- SlickModel.contests if contest.id === submit.contest && contest.polygonId =!= ""
    } yield (submit.id, submit.contest, submit.team, submit.problem, submit.arrived, lang.moduleID, submit.source, contest.polygonId)

  val insertTestingSubmitURL =
    SlickModel.testings.map(x => (x.submit, x.problemURL)) returning SlickModel.testings.map(_.id)

  val addCompileResult = SlickModel.results.map(x => (x.testingID, x.resultCode, x.testID, x.timeMs, x.memoryBytes, x.testerOutput, x.testerError))

  val addTestResult = SlickModel.results.map(x => (x.testingID, x.resultCode, x.testID, x.timeMs, x.memoryBytes, x.returnCode, x.testerOutput, x.testerError, x.testerReturnCode))

  private[this] def getCustomTestByID(id: Rep[Long]) =
    for {
      c <- SlickModel.customTests if c.id === id
      lang <- SlickModel.compilers if lang.id === c.language
    } yield (c.id, c.contest, c.team, c.arrived, lang.moduleID, c.source, c.input)

  val customTestByID = Compiled(getCustomTestByID _)
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

    db.run(contestsWithPolygonID.result).map(_.map(x =>
      Contest(x._1, x._2, PolygonContestId(x._3), x._4)
    ))
  }

  private[this] def maybeUpdateContestName(contestId: Int, rowName: String, contestName: String) = {
    import CPModel._
    import slick.jdbc.PostgresProfile.api._
    if (rowName != contestName)
      Some(contestNameByID(contestId).update(contestName))
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
    import org.stingray.contester.dbmodel.MyPostgresProfile.api._

    val pfixes = SlickModel.problems.filter(x => x.contestID === row.id).result.flatMap { probs =>
      val problemMap = probs.filter(_.contest == row.id).map(x => x.id.toUpperCase -> x).toMap

      val deletes = (problemMap.keySet -- contest.problems.keySet).toSeq.map { problemId =>
        SlickModel.problems.filter(x => x.contestID === row.id && x.id === problemId).delete
      }

      val updates = contest.problems.map(x => x -> problemMap.get(x._1)).collect {
        case ((problemId, polygonProblem), Some(problemRow))
          if (problemRow.name != polygonProblem.getTitle(row.Language) || problemRow.tests != polygonProblem.testCount) =>
          val problemTitle = polygonProblem.getTitle(row.Language)
          logger.trace(s"$problemRow | ${problemTitle} | ${polygonProblem.testCount}")
          logger.trace(s"replacing problem $problemId := $polygonProblem")

          SlickModel.problems.insertOrUpdate(Problem(row.id, problemId, problemTitle, polygonProblem.testCount))

        case (((problemId, polygonProblem), None)) =>
          val problemTitle = polygonProblem.getTitle(row.Language)
          logger.trace(s"adding problem $problemId := $polygonProblem")
          SlickModel.problems.insertOrUpdate(Problem(row.id, problemId, problemTitle, polygonProblem.testCount))
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
