package org.stingray.contester.problems

import org.stingray.contester.invokers.Sandbox
import com.twitter.util.Future
import collection.immutable

/**
 * A proxy for parent problem to limit the test set.
 * @param parent Problem.
 * @param tests Test set limit.
 */
private class ProblemProxy(parent: Problem, protected val tests: Seq[Int]) extends Problem {
  def getTest(key: Int): Test = parent.getTest(key)
}

/**
 * A problem from the testing engine point of view is a map TestID->Test.
 */
trait Problem extends immutable.SortedMap[Int, Test] {
  /**
   * Override this method to provide sequence of tests.
   * @return Sequence of tests.
   */
  protected def tests: Seq[Int]

  /**
   * Override this method to provide tests themselves.
   * @param key Test ID.
   * @return Test.
   */
  def getTest(key: Int): Test

  implicit def ordering: Ordering[Int] = Ordering.Int

  def iterator: Iterator[(Int, Test)] =
    tests.flatMap(i => get(i).map(i -> _)).iterator

  def rangeImpl(from: Option[Int], until: Option[Int]): immutable.SortedMap[Int, Test] = {
    val left = from.map(l => tests.filter(_ >= l)).getOrElse(tests)
    val right = until.map(r => left.filter(_ <= r)).getOrElse(left)

    new ProblemProxy(this, right)
  }

  def -(key: Int): immutable.SortedMap[Int, Test] =
    new ProblemProxy(this, tests.filterNot(_ == key))

  def get(key: Int): Option[Test] =
    if (tests.contains(key))
      Some(getTest(key))
    else
      None
}

/**
 * Identifier for a problem already in gridfs.
 * Has helper methods for all gridfs file names.
 */
trait ProblemID {
  /**
   * Defines problem ID in pdb/gridfs. Needs to be storage-compatible.
   * @return Problem ID in pdb/gridfs.
   */
  def pid: String

  /**
   * Revision of a problem.
   * @return Revision.
   */
  def revision: Int

  /**
   * Constructed ID for a problem. Used as part of paths and as _id in manifest collection.
   */
  final val pdbId = pid + "/" + revision.toString

  override def toString = "ProblemID(%s, %d)".format(pid, revision)

  override def hashCode() =
    pid.hashCode() + revision.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case other: ProblemID => (pid == other.pid) && (revision == other.revision)
    case _ => super.equals(obj)
  }

  /**
   * Basename used for sanitizing.
   *
   * TODO: Replace with shortened/hash?
   * @return Filesystem-compatible name.
   */
  final def destName = pid.replace('/', '.').replace(':', '.') + "." + revision.toString

  /**
   * Archive name written out for saniziting.
   * @return FIlesystem-compatible name.
   */
  final def zipName = destName + ".zip"

  /**
   * Prefix in gridfs for all problem-related things.
   * @return Gridf-compatible prefix.
   */
  final def prefix = "problem/" +  pdbId

  /**
   * Shorthand for creating all other paths.
   * @param suffix
   * @return
   */
  final private def dbName(suffix: String) =
    prefix + "/" + suffix

  /**
   * Checker path.
   * @return
   */
  final def checkerName = dbName("checker")

  /**
   * Prefix for a given test id.
   * @param testId Test id.
   * @return
   */
  final def testPrefix(testId: Int) = dbName("tests/" + testId + "/")

  /**
   * Input data path.
   * @param testId Test id.
   * @return
   */
  final def inputName(testId: Int) = testPrefix(testId) + "input.txt"

  /**
   * Answer file path.
   * @param testId Test id.
   * @return
   */
  final def answerName(testId: Int) = testPrefix(testId) + "answer.txt"

  /**
   * Archive path.
   * @return
   */
  final def archiveName = dbName("archive")

  /**
   * Interactor path.
   * @return
   */
  final def interactorName = dbName("interactor")
}

/**
 * Simplest problem ID, just by pid/revision.
 * @param pid Problem pid.
 * @param revision Problem revision.
 */
case class SimpleProblemID(override val pid: String, override val revision: Int) extends ProblemID

/**
 * A problem from testing engine point of view, built from ProblemID and additional info
 * (pulled from manifest, presumably).
 * @param pdb ProblemDB client.
 * @param id ProblemID instance.
 * @param testCount How many tests.
 * @param timeLimitMicros Time limit in microseconds.
 * @param memoryLimit Memory limit in bytes.
 * @param testerName Original file name for checker.
 * @param answers Set of test numbers for which answers are provided.
 * @param interactorName Some(original file name for interactor) or none.
 * @param stdio Is this a stdio problem or not.
 */
class PDBProblem(val pdb: ProblemDb, val id: ProblemID, val testCount: Int, val timeLimitMicros: Long,
                 val memoryLimit: Long, val testerName: String, val answers: Set[Int],
                 val interactorName: Option[String], val stdio: Boolean) extends Problem {
  def tests: Seq[Int] = 1 to testCount

  def getTest(key: Int): Test =
    new PDBTest(this, key)

  def interactive = interactorName.isDefined

  override def toString = "PDBProblem(%s, %d)".format(id.pid, id.revision)
}

object PDBProblem {
  def apply(pdb: ProblemDb, id: ProblemID, m: ProblemManifest) =
    new PDBProblem(pdb, id, m.testCount, m.timeLimitMicros, m.memoryLimit, m.testerName, m.answers.toSet,
      m.interactorName, m.stdio)
}

private class PDBTest(val problem: PDBProblem, val testId: Int) extends Test with TestLimits {
  override def toString = "PDBTest(%s, %d)".format(problem, testId)

  def memoryLimit: Long = problem.memoryLimit

  def timeLimitMicros: Long = problem.timeLimitMicros

  def getLimits(moduleType: String): TestLimits = this

  private[this] def putAsset(sandbox: Sandbox, what: String, where: String) =
    sandbox.putGridfs(what, where).map { r =>
      if (r.isEmpty) throw new TestAssetNotFoundException(what)
    }

  def prepareInput(sandbox: Sandbox): Future[Unit] =
    putAsset(sandbox, problem.id.inputName(testId), "input.txt")

  def prepareTester(sandbox: Sandbox): Future[Unit] =
    if (problem.answers(testId))
      putAsset(sandbox, problem.id.answerName(testId), "answer.txt")
    else Future.Done

  def prepareTesterBinary(sandbox: Sandbox): Future[String] =
    putAsset(sandbox, problem.id.checkerName, problem.testerName).map(_ => problem.testerName)

  def prepareInteractorBinary(sandbox: Sandbox): Future[String] =
    problem.interactorName.map { i =>
      putAsset(sandbox, problem.id.interactorName, i).map(_ => i)
    }.getOrElse(Future.exception(new TestAssetNotFoundException(problem.id.interactorName)))


  def interactive: Boolean = problem.interactive

  def stdio: Boolean = problem.stdio
}

trait ProblemHandle