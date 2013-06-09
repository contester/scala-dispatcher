package org.stingray.contester.problems

import org.stingray.contester.invokers.Sandbox
import com.twitter.util.Future
import collection.immutable

class ProblemProxy(parent: Problem, protected val tests: Seq[Int]) extends Problem {
  def getTest(key: Int): Test = parent.getTest(key)
}

trait Problem extends immutable.SortedMap[Int, Test] {
  protected def tests: Seq[Int]
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

trait ProblemT {
  def id: String
  def revision: Int

  override def toString = "ProblemT(%s, %d)".format(id, revision)

  override def hashCode() =
    id.hashCode() + revision.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case other: ProblemT => (id == other.id) && (revision == other.revision)
    case _ => super.equals(obj)
  }

  def destName = id.replace('/', '.').replace(':', '.') + "." + revision.toString
  def zipName = destName + ".zip"
  def prefix = Seq("problem", id, revision.toString).mkString("/")
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

case class SimpleProblemT(override val id: String, override val revision: Int) extends ProblemT

class PDBProblem(val pdb: ProblemDb, val id: ProblemT, val testCount: Int, val timeLimitMicros: Long,
                 val memoryLimit: Long, val testerName: String, val answers: Set[Int],
                 val interactorName: Option[String], val stdio: Boolean) extends Problem {


  def tests: Seq[Int] = 1 to testCount

  def getTest(key: Int): Test =
    new PDBTest(this, key)

  def interactive = interactorName.isDefined

  override def toString = "PDBProblem(%s, %d)".format(id.id, id.revision)
}

object PDBProblem {
  def apply(pdb: ProblemDb, id: ProblemT, m: ProblemManifest) =
    new PDBProblem(pdb, id, m.testCount, m.timeLimitMicros, m.memoryLimit, m.testerName, m.answers.toSet,
      m.interactorName, m.stdio)
}

class PDBTest(val problem: PDBProblem, val testId: Int) extends Test with TestLimits {
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