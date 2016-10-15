package org.stingray.contester.dispatcher

import akka.actor.{Actor, Props}
import com.twitter.util.Future
import slick.jdbc.{GetResult, JdbcBackend}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.immutable
import grizzled.slf4j.Logging
import org.stingray.contester.polygon._

import scala.concurrent.{Future => ScalaFuture}


case class ContestRow(id: Int, name: String, polygonId: PolygonContestId, schoolMode: Boolean, Language: String)
case class ProblemRow(contest: Int, id: String, tests: Int, name: String, rating: Int)

class ContestNotFoundException(id: Int) extends Throwable(id.toString)


object ContestTableScanner {
  case object Rescan

  case class ContestMap(map: Map[ContestRow, ContestWithProblems])

  case class GetContest(id: Int)
}

class ContestTableScanner(db: JdbcBackend#DatabaseDef, resolver: PolygonClient)
  extends Actor with Logging {
  import slick.driver.MySQLDriver.api._
  import org.stingray.contester.utils.Dbutil._
  import ContestTableScanner._

  implicit val getContestRow = GetResult(r =>
    ContestRow(r.nextInt(), r.nextString(), PolygonContestId(r.nextString()), r.nextBoolean(), r.nextString())
  )

  private def getContestsFromDb =
    db.run(sql"select ID, Name, PolygonID, SchoolMode, Language from Contests where PolygonID != ''".as[ContestRow])

  implicit val getProblemRow = GetResult(r =>
    ProblemRow(r.nextInt(), r.nextString(), r.nextInt(), r.nextString(), r.nextInt())
  )

  private def getProblemsFromDb =
    db.run(sql"select Contest, ID, Tests, Name, Rating from Problems".as[ProblemRow])

  private[this] def maybeUpdateContestName(contestId: Int, rowName: String, contestName: String): Option[Future[Unit]] = {
    import org.stingray.contester.utils.Fu._
    if (rowName != contestName)
      Some(db.run(sqlu"update Contests set Name = $contestName where ID = $contestId").unit)
    else
      None
  }

  import org.stingray.contester.utils.Fu._

  def getNewContestMap =
    getContestsFromDb.flatMap { contests =>
      trace(s"received $contests")
      Future.collect(contests.map(x => resolver.getContest(x.polygonId).map(p => x -> p))).map(_.toMap)
    }

  def updateContest(row: ContestRow, contest: ContestWithProblems): Future[Unit] = {
    val nameChange = maybeUpdateContestName(row.id, row.name, contest.contest.getName(row.Language))

    getProblemsFromDb.flatMap { problems =>
      val problemMap = problems.map(x => x.id.toUpperCase -> x).toMap
      info(s"loaded old problems: $problemMap")

      val deletes = (problemMap.keySet -- contest.problems.keySet).map { problemId =>
        db.run(sqlu"delete from Problems where Contest = ${row.id} and ID = $problemId").unit
      }.toSeq

      val updates = contest.problems.map(x => x -> problemMap.get(x._1)).collect {
        case ((problemId, polygonProblem), Some(problemRow))
          if (problemRow.name != polygonProblem.getTitle(row.Language) || problemRow.tests != polygonProblem.testCount) =>
        db.run(sqlu"""replace Problems (Contest, ID, Tests, Name, Rating) values (${row.id}, ${problemId},
          ${polygonProblem.testCount}, ${polygonProblem.getTitle(row.Language)}, 30)""").unit
        case (((problemId, polygonProblem), None)) =>
          info(s"adding problem $problemId := $polygonProblem")
        db.run(sqlu"""replace Problems (Contest, ID, Tests, Name, Rating) values (${row.id}, ${problemId},
          ${polygonProblem.testCount}, ${polygonProblem.getTitle(row.Language)}, 30)""").unit
      }
      Future.collect(deletes ++ updates ++ nameChange)
    }.unit
  }

  def updateContests(m: Map[ContestRow, ContestWithProblems]) =
    Future.collect(m.map(x => updateContest(x._1, x._2)).toSeq).unit

  import scala.concurrent.duration._

  override def receive = {
    case ContestMap(map) =>
      trace("Contest map received")

    case Rescan =>
      trace("Starting contest rescan")
      getNewContestMap.foreach { newMap =>
        trace(s"Contest rescan done, $newMap")
        self ! ContestMap(newMap)
        updateContests(newMap).onComplete { r =>
          trace("updateContests result: $r")
          trace("Scheduling next rescan")
          context.system.scheduler.scheduleOnce(60 seconds, self, Rescan)
        }
      }
  }

  context.system.scheduler.scheduleOnce(0 seconds, self, Rescan)
}
