package org.stingray.contester.problems

import com.twitter.util.Future
import org.stingray.contester.common.ProblemDb
import org.stingray.contester.polygon.SanitizedProblem
import org.stingray.contester.invokers.Sandbox

class TestAssetNotFoundException(what: String) extends scala.Throwable(what)

trait TestLimits {
  def memoryLimit: Long
  def timeLimitMicros: Long
}

trait Test {
  def testId: Int
  def getLimits(moduleType: String): TestLimits
  def prepareInput(sandbox: Sandbox): Future[Unit]
  def prepareTester(sandbox: Sandbox): Future[Unit]
  def prepareTesterBinary(sandbox: Sandbox): Future[String]
  def prepareInteractorBinary(sandbox: Sandbox): Future[String]
  def interactive: Boolean
  def stdio: Boolean
}

class G4Test(val p: SanitizedProblem, pdb: ProblemDb, val testId: Int) extends Test with TestLimits {
  private val name = "Test(%s, %d)".format(p, testId)

  val interactive = p.interactive && (testId <= 15 || !p.semi)
  val stdio = p.stdio
  val memoryLimit: Long = p.memoryLimit
  val timeLimitMicros: Long = p.timeLimit * 1000

  override def toString =
    name

  def getLimits(moduleType: String) = this

  private def putAsset(sandbox: Sandbox, what: String, where: String) =
    sandbox.putGridfs(what, where).map { r =>
      if (r.isEmpty) throw new TestAssetNotFoundException(what)
    }

  def prepareInput(sandbox: Sandbox) =
    putAsset(sandbox, p.inputName(testId), "input.txt")

  def prepareTester(sandbox: Sandbox): Future[Unit] =
    if (p.manifest.answers.toSet(testId))
      putAsset(sandbox, p.answerName(testId), "answer.txt")
    else Future.Done

  def prepareTesterBinary(sandbox: Sandbox): Future[String] =
    putAsset(sandbox, p.checkerName, p.manifest.testerName).map(_ => p.manifest.testerName)

  def prepareInteractorBinary(sandbox: Sandbox): Future[String] =
    p.manifest.interactorName.map { i =>
      putAsset(sandbox, p.interactorName, i).map(_ => i)
    }.getOrElse(Future.exception(new TestAssetNotFoundException(p.interactorName)))
}