package org.stingray.contester.dispatcher

import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common.ProblemDb
import org.stingray.contester.polygon._
import org.stingray.contester.utils.Utils
import org.stingray.contester.invokers.InvokerRegistry

class ProblemData(pclient: SpecializedClient, pdb: ProblemDb, invoker: InvokerRegistry) extends Logging {
  private val contestByPid = new ContestByPid(pclient, pdb)
  private val problemByPid = new ProblemByPid(pclient, pdb)
  private val manifestByPid = new ProblemManifestByProblem(pdb, invoker)

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
      contestByPid.getContestByPid(contestPid).flatMap { contest =>
        Future.collect(contest.problems.map(p => problemByPid.getProblemByPid(p._2).map(p._1 -> _)).toSeq)
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
      .flatMap(manifestByPid.scan(_)).unit

  import com.twitter.util.TimeConversions._

  def rescan: Future[Unit] =
    scan(getActiveContests).rescue {
      case _ => Future.Done
    }.flatMap(_ => Utils.later(1.minute).onSuccess(_ => rescan))

  def getProblemInfo(ref: Any, contestPid: Int, problemId: String): Future[SanitizedProblem] = {
    addContest(ref, contestPid)
      contestByPid.getContestByPid(contestPid).flatMap { contest =>
        problemByPid.getProblemByPid(contest.problems(problemId.toUpperCase)).flatMap { problem =>
          manifestByPid.getByProblem(problem).map { manifest =>
            problem.sanitized(manifest, pdb)
          }
        }
      }
  }

}
