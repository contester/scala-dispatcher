package org.stingray.contester.dispatcher

import collection.immutable
import com.twitter.util.{Promise, Future}
import com.twitter.util.TimeConversions._
import grizzled.slf4j.Logging
import org.stingray.contester.db.ConnectionPool
import org.stingray.contester.polygon.{ContestHandle, ContestWithProblems}
import org.stingray.contester.utils.Utils
import java.net.URL

case class ContestRow(id: Int, name: String, polygonId: Int, schoolMode: Boolean, Language: String)
case class ProblemRow(contest: Int, id: String, tests: Int, name: String, rating: Int)

class ContestNotFoundException(id: Int) extends Throwable(id.toString)

class ContestTableScanner(d: ProblemData, db: ConnectionPool, polygonBase: URL) extends Function[Int, Future[ContestHandle]] with Logging {
  private def getContestHandle(id: Int): ContestHandle = {
    val result = new ContestHandle(new URL(polygonBase, "c/" + id + "/"))
    result
  }

  private def getContestsFromDb: Future[Seq[ContestRow]] =
    db.select("select ID, Name, SchoolMode, PolygonID, Language from Contests where PolygonID != 0") { row =>
      ContestRow(row.getInt("ID"), row.getString("Name"), row.getInt("PolygonID"), row.getInt("SchoolMode") == 1, row.getString("Language").toLowerCase)
    }

  private def getProblemsFromDb: Future[Seq[ProblemRow]] =
    db.select("select Contest, ID, Tests, Name, Rating from Problems") { row =>
      ProblemRow(row.getInt("Contest"), row.getString("ID"), row.getInt("Tests"),
        row.getString("Name"), row.getInt("Rating"))
    }

  private[this] var data: Map[Int, ContestRow] = new immutable.HashMap[Int, ContestRow]()
  private[this] var nextScan = new Promise[Unit]()

  private[this] def maybeUpdateContestName(contestId: Int, rowName: String, contestName: String): Option[Future[Unit]] =
    if (rowName != contestName)
      Some(db.execute("update Contests set Name = ? where ID = ?", contestName, contestId).unit)
    else
      None

  private def singleContest(r: ContestRow, c: ContestWithProblems, oldp: Seq[ProblemRow]): Future[Unit] = {
    val m = oldp.filter(_.contest == r.id).map(v => v.id.toUpperCase -> v).toMap

    Future.collect(maybeUpdateContestName(r.id, r.name, c.getName(r.Language)).toSeq ++
    (m.keySet -- c.problems.keySet).toSeq.map { contestId =>
      db.execute("delete from Problems where Contest = ? and ID = ?", r.id, contestId).unit
    } ++
    c.problems.map(x => x -> m.get(x._1))
      .filter {
      case (x, o) =>
      !(o.isDefined && o.get.name == x._2.getTitle(r.Language) && o.get.tests == x._2.testCount)
      }.map {
      case (x, o) =>
        db.execute("replace Problems (Contest, ID, Tests, Name, Rating) values (?, ?, ?, ?, ?)",
          r.id, x._1, x._2.testCount, x._2.getTitle(r.Language), 30).unit
    }).unit
  }

  private def updateContests(contestList: Iterable[ContestRow]): Future[Unit] = {
    d.getContests(contestList.map(_.polygonId).toSet.toSeq.map(getContestHandle)).join(getProblemsFromDb)
      .flatMap {
      case (contests, problems) =>
        Future.collect(contests.flatMap(x => contestList.filter(h => getContestHandle(h.polygonId) == x._1).map(singleContest(_, x._2, problems))).toSeq)
    }.unit
  }

  def getNewContestMap: Future[Map[Int, ContestRow]] =
    getContestsFromDb.map(_.map(v => v.id -> v).toMap)

  def apply(key: Int): Future[ContestHandle] =
    synchronized {
      data.get(key).map(x => Future.value(getContestHandle(x.polygonId))).getOrElse(nextScan.map(_ => getContestHandle(data(key).polygonId)))
    }

  def scan: Future[Unit] = {
    trace("Started scanning Contest/Problem tables")
    getNewContestMap.flatMap { newMap =>
      trace("Finished scanning Contests, publishing the map")
      synchronized {
        data = newMap
        nextScan.setValue()
        nextScan = new Promise[Unit]()
      }
      updateContests(newMap.values)
    }
  }

  def rescan: Future[Unit] =
    scan.onFailure(error("rescan", _)).rescue {
      case _ => Future.Done
    }.flatMap { _ =>
      Utils.later(15.second).flatMap(_ => rescan)
    }
}
