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

/**
 * Identifier for a problem already in gridfs.
 * Has helper methods for all gridfs file names.
 */
trait ProblemWithRevision {
  /**
    * Defines problem ID in pdb/gridfs. Needs to be storage-compatible.
 *
   * @return Problem ID in pdb/gridfs.
   */
  def pid: String

  /**
   * Revision of a problem.
 *
   * @return Revision.
   */
  def revision: Long

  /**
   * Constructed ID for a problem. Used as part of paths and as _id in manifest collection.
   */
  final val pdbId = pid + "/" + revision.toString

  override def toString = "ProblemID(%s, %d)".format(pid, revision)

  override def hashCode() =
    pid.hashCode() + revision.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case other: ProblemWithRevision => (pid == other.pid) && (revision == other.revision)
    case _ => super.equals(obj)
  }

  /**
   * Prefix in gridfs for all problem-related things.
 *
   * @return Gridf-compatible prefix.
   */
  final def prefix = "problem/" +  pdbId

  /**
   * Shorthand for creating all other paths.
 *
   * @param suffix
   * @return
   */
  final private def dbName(suffix: String) =
    prefix + "/" + suffix

  /**
   * Checker path.
 *
   * @return
   */
  final def checkerName = dbName("checker")

  /**
   * Prefix for a given test id.
 *
   * @param testId Test id.
   * @return
   */
  final def testPrefix(testId: Int) = dbName("tests/" + testId + "/")

  /**
   * Input data path.
 *
   * @param testId Test id.
   * @return
   */
  final def inputName(testId: Int) = testPrefix(testId) + "input.txt"

  /**
   * Answer file path.
 *
   * @param testId Test id.
   * @return
   */
  final def answerName(testId: Int) = testPrefix(testId) + "answer.txt"

  /**
   * Archive path.
 *
   * @return
   */
  final def archiveName = dbName("archive")

  /**
   * Interactor path.
 *
   * @return
   */
  final def interactorName = dbName("interactor")
}

/**
 * Simplest problem ID, just by pid/revision.
 *
 * @param pid      Problem pid.
 * @param revision Problem revision.
 */
class SimpleProblemWithRevision(override val pid: String, override val revision: Long) extends ProblemWithRevision

case class ProblemHandle(handle: String) extends AnyVal