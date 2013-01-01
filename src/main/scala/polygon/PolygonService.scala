package org.stingray.contester.polygon

import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems
import problems._

abstract class CachingScanner[KeyType, ValueType] extends Function[KeyType, Future[ValueType]] {
  private val cache = new mutable.HashMap[KeyType, ValueType]()
  private val futures = new mutable.HashMap[KeyType, Future[ValueType]]()

  private def updateCache(key: KeyType, result: ValueType): Unit =
    synchronized {
      cache(key) = result
    }

  private def removeFuture(key: KeyType): Unit =
    synchronized {
      futures.remove(key)
    }


}


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

class ContestByPid(client: SpecializedClient, pdb: PolygonDb) extends Logging {
  private val data = mutable.HashMap[Int, Future[ContestDescription]]()

  private def getWithDb(pid: Int) =
    pdb.getContestDescription(pid).flatMap(_.headOption.map(Future.value(_)).getOrElse(getAndUpdateDb(pid)))

  private def getAndUpdateDb(contestId: Int) =
    client.getContest(contestId).onSuccess(pdb.setContestDescription(contestId, _))

  def getContestByPid(pid: Int) =
    data.synchronized { data.getOrElseUpdate(pid, getWithDb(pid)) }

  def scan(contests: Seq[Int]) = {
    val prevContests = data.keySet
    Future.collect(contests.map { contestId =>
      getAndUpdateDb(contestId).map((contestId -> _))
    }).map(_.toMap).map { newMap =>
      data.synchronized {
        prevContests.foreach(data.remove(_))
        newMap.foreach(x => data(x._1) = Future.value(x._2))
      }
      newMap.values.toSeq
    }
  }
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