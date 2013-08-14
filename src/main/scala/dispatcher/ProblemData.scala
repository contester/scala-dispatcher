package org.stingray.contester.dispatcher

import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.polygon._
import org.stingray.contester.utils.Utils
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems.Problem

class ContesterProblemData(invoker: InvokerRegistry) extends Logging {
  private val contestByPid = new ContestByPid(pclient, pdb)
  private val problemByPid = new ProblemByPid(pclient, pdb)
  private val sanitizer = PolygonSanitizer(pdb, pclient, invoker)

  private val activeContests = new mutable.HashMap[Any, mutable.Set[Int]]()

  var rescanFuture = rescan

  private def getActiveContests: Seq[Int] =
    activeContests.values.fold(Set())(_ ++ _).toSeq

  private def setContests(ref: Any, contests: Seq[Int]) =
    activeContests.synchronized {
      activeContests(ref) = mutable.Set(contests:_*)
    }

  private def addContest(ref: Any, contest: Int) =
    activeContests.synchronized {
      activeContests.getOrElseUpdate(ref, mutable.Set[Int]()) += contest
    }

  def getContests(ref: Any, contests: Seq[Int]): Future[Map[Int, ContestWithProblems]] = {
    setContests(ref, contests)
    Future.collect(contests.map { contestPid =>
      contestByPid(contestPid).flatMap { contest =>
        Future.collect(contest.problems.map(p => problemByPid(p._2).map(p._1 -> _)).toSeq)
          .map(v => contestPid -> new ContestWithProblems(contest, v.toMap))
      }
    }).map(_.toMap)
  }

  def remove(ref: Any) =
    activeContests.synchronized {
      activeContests.remove(ref)
    }

  private def scan(contests: Seq[Int]): Future[Unit] =
    contestByPid.scan(contests).map(_.flatMap(_.problems.values))
      .flatMap(problemByPid.scan(_))
      .flatMap(sanitizer.scan(_)).unit

  import com.twitter.util.TimeConversions._

  def rescan: Future[Unit] =
    scan(getActiveContests).rescue {
      case _ => Future.Done
    }.flatMap(_ => Utils.later(1.minute).onSuccess(_ => rescan))

  def getProblemInfo(ref: Any, contestPid: Int, problemId: String): Future[Problem] = {
    addContest(ref, contestPid)
      contestByPid(contestPid).flatMap { contest =>
        problemByPid(contest.problems(problemId.toUpperCase)).flatMap { problem =>
          sanitizer(problem)
        }
      }
  }

}
