package org.stingray.contester.problems

import com.twitter.io.Buf
import com.twitter.util.Future

trait ProblemServerInterface {
  def getMostRecentProblem(problem: ProblemHandle): Future[Option[ProblemBase]]
}

trait ProblemDb extends ProblemServerInterface {
  def setProblem(manifest: SimpleProblemManifest): Future[ProblemBase]
  def getProblem(problem: ProblemHandleWithRevision): Future[Option[ProblemBase]]
}

trait ProblemFileStore {
  def baseUrl: String
  def ensureProblemFile(problemArchiveName: String, getFn: => Future[Buf]): Future[Unit]
}

trait SanitizeDb extends ProblemDb with ProblemFileStore