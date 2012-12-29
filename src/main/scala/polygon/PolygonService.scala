package org.stingray.contester.polygon

import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems
import org.stingray.contester.engine.Sanitizer
import problems.{ProblemManifest, SanitizeDb, Problem}

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

class ProblemManifestByProblem(pdb: SanitizeDb, client: SpecializedClient, invoker: InvokerRegistry) extends Logging {
  private val data = mutable.HashMap[PolygonProblem, Future[Problem]]()

  private def getWithDb(pid: PolygonProblem): Future[Problem] =
    pdb.getProblem(pid).flatMap(_.headOption.map(Future.value(_)).getOrElse(getAndUpdateDb(pid)))

  // TODO: Don't getProblemFile in sanitizer;
  private def callSanitize(pid: PolygonProblem): Future[ProblemManifest] =
    invoker("zip", pid, "sanitize")(Sanitizer(_, pid))

  private def getAndUpdateDb(pid: PolygonProblem): Future[Problem] =
    pdb.getProblemFile(pid, client.getProblemFile(pid)).flatMap(_ =>
      callSanitize(pid).flatMap(i => pdb.setProblem(pid, i)))

  def getByProblem(pid: PolygonProblem): Future[Problem] =
    data.synchronized { data.getOrElseUpdate(pid, getWithDb(pid))}

  def scan(pids: Seq[PolygonProblem]): Future[Unit] =
    Future.collect(pids.map(getByProblem(_))).unit
}