package org.stingray.contester.dispatcher

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.polygon._
import org.stingray.contester.utils.ValueCache
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems.{SanitizeDb, Problem}
import com.twitter.finagle.Service
import org.jboss.netty.buffer.ChannelBuffer

class ProblemData(pclient: Service[PolygonClientRequest, ChannelBuffer], pdb: ValueCache[PolygonCacheKey, String], sdb: SanitizeDb, invoker: InvokerRegistry) extends Logging {
  private val polygonService = new PolygonService(pclient, pdb)
  private val sanitizer = new PolygonSanitizer(sdb, pclient, invoker)

  def getContests(contests: Seq[ContestHandle]): Future[Map[ContestHandle, ContestWithProblems]] = {
    Future.collect(contests.map { contestPid =>
      polygonService.contests(contestPid).flatMap { contest =>
        Future.collect(contest.problems.map(p => polygonService.problems(p._2).map(p._1 -> _)).toSeq)
          .map { v => contestPid -> new ContestWithProblems(contest, v.toMap) }
      }
    }).map(_.toMap)
  }

  def getProblemInfo(ref: Any, contestPid: ContestHandle, problemId: String): Future[Problem] = {
      polygonService.contests(contestPid).flatMap { contest =>
        polygonService.problems(contest.problems(problemId.toUpperCase)).flatMap { problem =>
          sanitizer(problem)
        }
      }
  }
}
