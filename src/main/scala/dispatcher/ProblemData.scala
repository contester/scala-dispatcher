package org.stingray.contester.dispatcher

import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.polygon._
import org.stingray.contester.utils.{ValueCache, Utils}
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems.{SanitizeDb, Problem}
import com.twitter.finagle.Service
import org.jboss.netty.buffer.ChannelBuffer

class ProblemData(pclient: Service[PolygonClientRequest, ChannelBuffer], pdb: ValueCache[PolygonCacheKey, String], sdb: SanitizeDb, invoker: InvokerRegistry) extends Logging {
  private val polygonService = new PolygonService(pclient, pdb)
  private val sanitizer = new PolygonSanitizer(sdb, pclient, invoker)

  private val activeContests = new mutable.HashMap[Any, mutable.Set[ContestHandle]]()

  var rescanFuture = rescan

  private def getActiveContests: Seq[ContestHandle] =
    activeContests.values.fold(Set())(_ ++ _).toSeq

  private def setContests(ref: Any, contests: Seq[ContestHandle]) =
    activeContests.synchronized {
      activeContests(ref) = mutable.Set(contests:_*)
    }

  private def addContest(ref: Any, contest: ContestHandle) =
    activeContests.synchronized {
      activeContests.getOrElseUpdate(ref, mutable.Set[ContestHandle]()) += contest
    }

  def getContests(ref: Any, contests: Seq[ContestHandle]): Future[Map[ContestHandle, ContestWithProblems]] = {
    setContests(ref, contests)
    Future.collect(contests.map { contestPid =>
      polygonService.contests(contestPid).flatMap { contest =>
        Future.collect(contest.problems.map(p => polygonService.problems(p._2).map(p._1 -> _)).toSeq)
          .map { v => contestPid -> new ContestWithProblems(contest, v.toMap) }
      }
    }).map(_.toMap)
  }

  def remove(ref: Any) =
    activeContests.synchronized {
      activeContests.remove(ref)
    }

  private def scan(contests: Seq[ContestHandle]): Future[Unit] =
    polygonService.contests.scan(contests).map(_.flatMap(_.problems.values))
      .flatMap(polygonService.problems.scan(_))
      .flatMap(sanitizer.scan(_)).unit

  import com.twitter.util.TimeConversions._

  def rescan: Future[Unit] =
    scan(getActiveContests).rescue {
      case _ => Future.Done
    }.flatMap(_ => Utils.later(1.minute).onSuccess(_ => rescan))

  def getProblemInfo(ref: Any, contestPid: ContestHandle, problemId: String): Future[Problem] = {
    addContest(ref, contestPid)
      polygonService.contests(contestPid).flatMap { contest =>
        polygonService.problems(contest.problems(problemId.toUpperCase)).flatMap { problem =>
          sanitizer(problem)
        }
      }
  }
}
