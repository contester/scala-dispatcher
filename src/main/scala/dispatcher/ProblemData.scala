package org.stingray.contester.dispatcher

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.polygon._
import org.stingray.contester.utils.ValueCache
import org.stingray.contester.problems.{SanitizeDb, Problem}
import com.twitter.finagle.Service
import org.jboss.netty.buffer.ChannelBuffer
import org.stingray.contester.engine.InvokerSimpleApi

class ProblemData(pclient: Service[PolygonClientRequest, ChannelBuffer], pdb: ValueCache[PolygonCacheKey, String],
                  sdb: SanitizeDb, invoker: InvokerSimpleApi) extends Logging {
  val polygonService = new PolygonService(pclient, pdb)
  val sanitizer = new PolygonSanitizer(sdb, pclient, invoker)

  def getContests(contests: Seq[ContestHandle]): Future[Map[ContestHandle, ContestWithProblems]] = {
    Future.collect(contests.map { contestPid =>
      polygonService.contests(contestPid).flatMap { contest =>
        Future.collect(contest.problems.map(p => polygonService.problems(p._2).map(p._1 -> _)).toSeq)
          .map { v => contestPid -> new ContestWithProblems(contest, v.toMap) }
      }
    }).map(_.toMap)
  }

  def getProblemHandle(contestPid: ContestHandle, problemId: String): Future[PolygonProblemHandle] =
    polygonService.contests(contestPid).map(_.problems(problemId.toUpperCase))

  def getProblem(handle: PolygonProblemHandle): Future[(PolygonProblem, Problem)] =
    polygonService.problems(handle).flatMap { problem =>
      sanitizer(problem).map((problem, _))
    }
}
