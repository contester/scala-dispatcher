package org.stingray.contester.polygon

import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems
import problems._
import org.stingray.contester.utils.ScannerCache

class ProblemByPid(client: SpecializedClient, pdb: PolygonDb) extends Logging {
  private val data = new mutable.HashMap[ProblemURL, Future[PolygonProblem]]()

  private def getDb(pid: ProblemURL) =
    pdb.getProblemDescription(pid).flatMap(_.headOption.map(Future.value(_)).getOrElse(getAndUpdateDb(pid)))

  private def getAndUpdateDb(problemId: ProblemURL) =
    client.getProblem(problemId).onSuccess(pdb.setProblemDescription(_))

  def scan(problems: Seq[ProblemURL]) = {
    val prevProblems = data.keySet
    debug("PrevProblems: " + prevProblems)
    Future.collect(problems.map(problemId => getAndUpdateDb(problemId).map(problemId -> _)))
      .map(_.toMap).map { newMap =>
      data.synchronized {
        prevProblems.foreach(data.remove(_))
        newMap.foreach(x => data(x._1) = Future.value(x._2))
      }
      newMap.values.toSeq
    }
  }

  def getProblemByPid(pid: ProblemURL) =
    data.synchronized { data.getOrElseUpdate(pid, getDb(pid)) }
}

class ContestByPid(client: SpecializedClient, pdb: PolygonDb) extends ScannerCache[Int, ContestDescription, ContestDescription] {
  def nearGet(key: Int): Future[Option[ContestDescription]] =
    pdb.getContestDescription(key)

  def nearPut(key: Int, value: ContestDescription): Future[ContestDescription] =
    pdb.setContestDescription(key, value).map(_ => value)

  def farGet(key: Int): Future[ContestDescription] =
    client.getContest(key)
}

class PolygonSanitizer(db: SanitizeDb, client: SpecializedClient, invoker: InvokerRegistry)
  extends ProblemDBSanitizer[PolygonProblem](db, new SimpleSanitizer(invoker)) {
  def getProblemFile(key: PolygonProblem): Future[Array[Byte]] =
    client.getProblemFile(key)
}

object PolygonSanitizer {
  def apply(db: SanitizeDb, client: SpecializedClient, invoker: InvokerRegistry) =
    new PolygonSanitizer(db, client, invoker)
}