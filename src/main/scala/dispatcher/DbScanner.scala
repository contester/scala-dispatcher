package org.stingray.contester.dispatcher

import akka.actor.{Props, Actor}
import slick.jdbc.{GetResult, JdbcBackend}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.immutable
import grizzled.slf4j.Logging
import org.stingray.contester.polygon.{ContestHandle, ContestWithProblems}
import scala.concurrent.Future

object PolygonContestId extends Logging {
  def parseSource(source: String): (String, Int) = {
    val splits = source.split(':')
    if (splits.length == 1)
      ("default", splits(0).toInt)
    else
      (splits(0), splits(1).toInt)
  }

  def apply(source: String): PolygonContestId = {
    val parsed = parseSource(source)
    trace((source, parsed))
    PolygonContestId(parsed._1, parsed._2)
  }

}

case class PolygonContestId(polygon: String, contestId: Int)

case class ContestRow(id: Int, name: String, polygonId: PolygonContestId, schoolMode: Boolean, Language: String)
case class ProblemRow(contest: Int, id: String, tests: Int, name: String, rating: Int)

class ContestNotFoundException(id: Int) extends Throwable(id.toString)

object ContestTableScanner {
  case object Rescan
  case class ContestMap(map: Map[Int, ContestRow])
  case class GetContest(id: Int)
  case class GetContestResponse(row: ContestHandle)

  def props(d: ProblemData, db: JdbcBackend#DatabaseDef, contestResolver: PolygonContestId => ContestHandle) =
    Props(classOf[ContestTableScanner], d, db, contestResolver)
}

class ContestTableScanner(d: ProblemData, db: JdbcBackend#DatabaseDef, contestResolver: PolygonContestId => ContestHandle)
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

  private[this] var data: Map[Int, ContestRow] = new immutable.HashMap[Int, ContestRow]()

  private[this] def maybeUpdateContestName(contestId: Int, rowName: String, contestName: String) =
    if (rowName != contestName)
      db.run(sqlu"update Contests set Name = $contestName where ID = $contestId")
    else
      Future.successful(0)

  private def singleContest(r: ContestRow, c: ContestWithProblems, oldp: Seq[ProblemRow]): Future[Unit] = {
    val m = oldp.filter(_.contest == r.id).map(v => v.id.toUpperCase -> v).toMap

    c.problems.values.foreach(d.sanitizer)

    val nameChange = maybeUpdateContestName(r.id, r.name, c.getName(r.Language))

    val deletes = Future.sequence((m.keySet -- c.problems.keySet).toSeq.map { problemId =>
      db.run(sqlu"delete from Problems where Contest = ${r.id} and ID = $problemId")
    })

    val updates =
    c.problems.map(x => x -> m.get(x._1))
      .collect {
      case ((problemId, polygonProblem), Some(problemRow))
        if (problemRow.name != polygonProblem.getTitle(r.Language) || problemRow.tests != polygonProblem.testCount) =>
        db.run(sqlu"""replace Problems (Contest, ID, Tests, Name, Rating) values (${r.id}, ${problemId},
          ${polygonProblem.testCount}, ${polygonProblem.getTitle(r.Language)}, 30)""")
      case (((problemId, polygonProblem), None)) =>
	db.run(sqlu"""replace Problems (Contest, ID, Tests, Name, Rating) values (${r.id}, ${problemId},
          ${polygonProblem.testCount}, ${polygonProblem.getTitle(r.Language)}, 30)""")
    }
    nameChange.zip(deletes).zip(Future.sequence(updates)).map(_ => ())
  }

  import org.stingray.contester.utils.Fu._

  private def updateContests(contestList: Iterable[ContestRow]): Future[Unit] = {
    val cmap = contestList.map(x => contestResolver(x.polygonId) -> x).toMap
    d.getContests(contestList.map(_.polygonId).toSet.toSeq.map(contestResolver))
      .zip(getProblemsFromDb)
      .flatMap {
      case (contests, problems) =>
        Future.sequence(
          contests.map {
            case (cHandle, cwp) =>
              singleContest(cmap(cHandle), cwp, problems)
          }
        )
    }.map(_ => ())
  }

  def getNewContestMap: Future[Map[Int, ContestRow]] =
    getContestsFromDb.map(_.map(v => v.id -> v).toMap)

  import scala.concurrent.duration._

  override def receive = {
    case ContestMap(map) =>
      trace("Contest map received")
      data = map

    case GetContest(cid) =>
      sender ! GetContestResponse(contestResolver(data(cid).polygonId))

    case Rescan =>
      trace("Starting contest rescan")
      getNewContestMap.foreach { newMap =>
        trace(s"Contest rescan done, $newMap")
        self ! ContestMap(newMap)
        updateContests(newMap.values).onComplete { _ =>
          trace("Scheduling next rescan")
          context.system.scheduler.scheduleOnce(60 seconds, self, Rescan)
        }
      }
  }

  context.system.scheduler.scheduleOnce(0 seconds, self, Rescan)
}
