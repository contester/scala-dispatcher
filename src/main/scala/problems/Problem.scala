package org.stingray.contester.problems

import com.twitter.util.Future
import org.stingray.contester.invokers.Sandbox

/**
 * A problem from the testing engine point of view is a map TestID->Test.
 */
trait Problem {
  /**
   * Override this method to provide sequence of tests.
 *
   * @return Sequence of tests.
   */
  protected def tests: Seq[Int]

  /**
   * Override this method to provide tests themselves.
 *
   * @param key Test ID.
   * @return Test.
   */
  def getTest(key: Int): Test

  def toSeq = tests.map(x => x -> getTest(x))
}

trait ProblemHandleWithRevision {
  def handle: String
  def revision: Long
}

trait ProblemAssetInterface {
  def checkerName: String
  def interactorName: String
  def inputName(testId: Int): String
  def answerName(testId: Int): String
}

trait ProblemArchiveInterface {
  def archiveName: String
}

case class StandardProblemAssetInterface(baseUrl: String, pdbId: StoragePrefix) extends ProblemAssetInterface with ProblemArchiveInterface {
  private[this] def prefix = "filer:" + baseUrl + "fs/problem/" +  pdbId.self
  private[this] def dbName(suffix: String) = prefix + "/" + suffix
  final def checkerName = dbName("checker")
  private[this] final def testPrefix(testId: Int) = dbName("tests/" + testId + "/")
  final def inputName(testId: Int) = testPrefix(testId) + "input.txt"
  final def answerName(testId: Int) = testPrefix(testId) + "answer.txt"
  final def archiveName = dbName("archive")
  final def interactorName = dbName("interactor")
}

case class ProblemHandle(handle: String) extends AnyVal