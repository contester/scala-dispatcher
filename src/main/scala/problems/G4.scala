package org.stingray.contester.problems

import com.twitter.util.Future
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

  def key: Future[Option[String]]
}
