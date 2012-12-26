package org.stingray.contester.polygon

import org.stingray.contester.common.ProblemDb
import org.stingray.contester.problems.ProblemTuple
import com.twitter.util.Future

class PolygonProblemDb(mongoHost: String, client: PolygonClient) extends ProblemDb(mongoHost) {
  def getProblemFile(problem: ProblemTuple): Future[Unit] =
    getProblemFile(problem, client.getProblemFile(problem))
}