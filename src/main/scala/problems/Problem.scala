package org.stingray.contester.problems

import org.stingray.contester.invokers.Sandbox
import com.twitter.util.Future
import collection.immutable

/**
 * A subset of tests for a problem.
 * @param parent Problem with tests.
 * @param tests Subset we export.
 */
private class ProblemProxy(parent: Problem, protected val tests: Seq[Int]) extends Problem {
  def getTest(key: Int): Test = parent.getTest(key)
}

/**
 * A problem trait, used by testing engine. testId -> Test, essentially.
 */
trait Problem extends immutable.SortedMap[Int, Test] {
  /**
   * Override this with list of tests, in testing order.
   * @return List of tests we have.
   */
  protected def tests: Seq[Int]

  /**
   * Override this with getter for test, by its id.
   * @param key Test ID.
   * @return Test with given ID.
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
 * A problem handle, from the data point of view. Names, suffixes, etc.
 */
trait ProblemID {
  /**
   * Defines the id in problem db.
   * @return Problem DB ID.
   */
  def pdbId: String

  override def toString = "ProblemID(%s)".format(pdbId)

  override def hashCode() =
    pdbId.hashCode

  override def equals(obj: Any): Boolean = obj match {
    case other: ProblemID => pdbId == other.pdbId
    case _ => super.equals(obj)
  }

  def destName = pdbId.replace('/', '.').replace(':', '.')
  def zipName = destName + ".zip"
  def prefix = Seq("problem", pdbId).mkString("/")
  def dbName(suffix: String) =
    prefix + "/" + suffix

  def manifestName = dbName("manifest")
  def problemXmlName = dbName("problem.xml")
  def checkerName = dbName("checker")
  def testPrefix(testId: Int) = dbName("tests/" + testId + "/")
  def inputName(testId: Int) = testPrefix(testId) + "input.txt"
  def answerName(testId: Int) = testPrefix(testId) + "answer.txt"
  def archiveName = dbName("archive")
  def interactorName = dbName("interactor")
}

/**
 * A simplest case of problem handle. Created when manifest already exists and there's no need to sanitize.
 * @param pdbId ID for the problem.
 */
case class SimpleProblemID(override val pdbId: String) extends ProblemID

/**
 * A problem from Problem DB.
 * @param pdb
 * @param id
 * @param testCount
 * @param timeLimitMicros
 * @param memoryLimit
 * @param testerName
 * @param answers
 * @param interactorName
 * @param stdio
 */
class PDBProblem(val pdb: ProblemDb, val id: ProblemID, val testCount: Int, val timeLimitMicros: Long,
                 val memoryLimit: Long, val testerName: String, val answers: Set[Int],
                 val interactorName: Option[String], val stdio: Boolean) extends Problem {
  def tests: Seq[Int] = 1 to testCount

  def getTest(key: Int): Test =
    new PDBTest(this, key)

  def interactive = interactorName.isDefined

  override def toString = "PDBProblem(%s, %d)".format(id.pdbId)
}

object PDBProblem {
  /**
   * Creates PDBProblem from pdb and id.
   * @param pdb
   * @param id
   * @param m
   * @return
   */
  def apply(pdb: ProblemDb, id: ProblemID, m: ProblemManifest) =
    new PDBProblem(pdb, id, m.testCount, m.timeLimitMicros, m.memoryLimit, m.testerName, m.answers.toSet,
      m.interactorName, m.stdio)
}

/**
 * A test for PDBProblem.
 * @param problem Problem.
 * @param testId Test ID.
 */
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

/**
 * New-style problem handle. This is a source handle, which will be resolved into ProblemID
 * Example: polygon+https://foo/bar/xxx
 */
trait ProblemHandle {
  /**
   * Return external problem URI.
   * @return
   */
  def toProblemURI: String
}
