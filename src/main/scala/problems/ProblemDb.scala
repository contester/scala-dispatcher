package org.stingray.contester.problems

import com.twitter.io.Buf
import com.twitter.util.Future

case class ProblemManifest(testCount: Int, timeLimitMicros: Long, memoryLimit: Long,
                           stdio: Boolean, testerName: String, answers: Iterable[Int], interactorName: Option[String]) {
  def toList = List(
    "testCount" -> testCount,
    "timeLimitMicros" -> timeLimitMicros,
    "memoryLimit" -> memoryLimit,
    "stdio" -> stdio,
    "testerName" -> testerName,
    "answers" -> answers) ++ interactorName.map("interactorName" -> _)
}

trait ProblemServerInterface {
  def getMostRecentProblem(problem: ProblemHandle): Future[Option[Problem]]
}

trait ProblemDb extends ProblemServerInterface {
  def setProblem(manifest: SimpleProblemManifest): Future[Problem]
  def getProblem(problem: ProblemHandleWithRevision): Future[Option[Problem]]
}

trait ProblemFileStore {
  def ensureProblemFile(problem: ProblemWithRevision, getFn: => Future[Buf]): Future[Unit]
}

trait SanitizeDb extends ProblemDb with ProblemFileStore