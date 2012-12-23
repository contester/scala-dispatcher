package org.stingray.contester.polygon

import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common.{ProblemManifest, ProblemDb}
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems.ProblemURL
import org.stingray.contester.engine.Sanitizer

class ProblemByPid(client: SpecializedClient, pdb: ProblemDb) extends Logging {
  private val data = new mutable.HashMap[ProblemURL, Future[Problem]]()

  private def getDb(pid: ProblemURL) =
    pdb.getProblem(pid).flatMap(_.headOption.map(Future.value(_)).getOrElse(getAndUpdateDb(pid)))

  private def getAndUpdateDb(problemId: ProblemURL) =
    client.getProblem(problemId).onSuccess(pdb.setProblem(_))

  def scan(problems: Seq[ProblemURL]) = {
    val prevProblems = data.keySet
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

class ContestByPid(client: SpecializedClient, pdb: ProblemDb) extends Logging {
  private val data = mutable.HashMap[Int, Future[Contest]]()

  private def getWithDb(pid: Int) =
    pdb.getContest(pid).flatMap(_.headOption.map(Future.value(_)).getOrElse(getAndUpdateDb(pid)))

  private def getAndUpdateDb(contestId: Int) =
    client.getContest(contestId).onSuccess(pdb.setContest(contestId, _))

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

class ProblemManifestByProblem(pdb: ProblemDb, invoker: InvokerRegistry) extends Logging {
  private val data = mutable.HashMap[Problem, Future[ProblemManifest]]()

  private def getWithDb(pid: Problem) =
    pdb.getProblemManifest(pid).flatMap(_.headOption.map(Future.value(_)).getOrElse(getAndUpdateDb(pid)))

  private def callSanitize(pid: Problem) =
    invoker.wrappedGetClear("zip", pid, "sanitize")(Sanitizer(_, pdb, pid))

  private def getAndUpdateDb(pid: Problem) =
    pdb.getProblemFile(pid).flatMap(_ =>
      callSanitize(pid).onSuccess(i => pdb.setProblemManifest(pid, i)))

  def getByProblem(pid: Problem) =
    data.synchronized { data.getOrElseUpdate(pid, getWithDb(pid))}

  def scan(pids: Seq[Problem]) =
    Future.collect(pids.map(getByProblem(_)))
}