package org.stingray.contester.engine

import org.stingray.contester.invokers.{SchedulingKey, CompilerInstance, RemoteFile, Sandbox}
import grizzled.slf4j.Logging
import util.matching.Regex
import com.twitter.util.Future
import org.stingray.contester.problems.{ProblemManifest, ProblemT}
import org.stingray.contester.modules.SevenzipHandler
import org.stingray.contester.ContesterImplicits._
import java.sql.Timestamp

class TesterNotFoundException extends scala.Throwable
class PdbStoreException(path: String) extends scala.Throwable(path)
class UnpackError extends scala.Throwable
class SanitizerError extends Throwable
class ProblemFileNotFound extends SanitizerError

trait ProblemDescription extends ProblemT with SchedulingKey {
  def interactive: Boolean
  def stdio: Boolean
  def testCount: Int
  def timeLimitMicros: Long
  def memoryLimit: Long

  protected def getTimestamp: Timestamp = EARLIEST
}

class ProblemSanitizer(sandbox: Sandbox, base: RemoteFile, problem: ProblemDescription) extends Logging {
  private[this] val testerRe = new Regex("^check\\.(\\S+)$")

  private[this] def detectGenerator =
    sandbox.glob(
      ("doall.*" :: ("Gen" + sandbox.i.pathSeparator + "doall.*") :: Nil).map(base ** _)
    )
      .map { g =>
      val m = g.isFile.map(v => v.ext.toLowerCase -> v).toMap
      m.get("cmd").orElse(m.get("bat")).orElse(m.values.headOption)
    }

  private[this] def useGenerator(gen: RemoteFile) =
    sandbox.getExecutionParameters(gen.name, Nil)
      .map(_.setCurrentAndTemp(gen.parent))
      .flatMap(sandbox.execute(_)).map(trace(_)).map(_ => this)

  private[this] def sanitize =
    detectGenerator.flatMap(g => g.map(useGenerator(_)).getOrElse(Future.value(this)))

  private[this] def findTester =
    sandbox.glob(base ** "*" :: Nil)
      .map { list =>
      val m = list.filter(_.isFile)
        .filter(x => testerRe.findFirstIn(x.basename.toLowerCase).nonEmpty)
        .map(x => x.ext.toLowerCase -> x).toMap
      m.get("exe").orElse(m.get("jar"))
    }.flatMap(_.map(Future.value(_)).getOrElse(Future.exception(new TesterNotFoundException)))

  private[this] def acceptableIds = Set("exe", "jar")

  private[this] def findInteractor =
    sandbox.glob(base ** "files/interactor.*" :: Nil)
      .map(_.isFile.filter(x => acceptableIds(x.ext)).toSeq.sortBy(_.ext).headOption)

  private[this] def findInteractorOption =
    if (problem.interactive)
      findInteractor
    else Future.None


  private[this] val testBase = base ** "tests"

  private[this] def analyzeLists(x: Iterable[String]) = {
    val s = x.toSet
    val m = (((1 to problem.testCount).map(problem.inputName)) ++ List(problem.checkerName)).toSet -- s
    if (m.nonEmpty) throw new PdbStoreException(m.head)
    (1 to problem.testCount).filter(i => s(problem.answerName(i)))
  }

  private[this] def storeProblem =
    findTester.join(findInteractorOption).flatMap {
      case (tester, interactor) =>
        sandbox.getGridfs((1 to problem.testCount).map(i => (testBase ** "%02d".format(i) -> problem.inputName(i))) ++
          (1 to problem.testCount).map(i => testBase ** "%02d.a".format(i) -> problem.answerName(i)) ++
          (tester -> problem.checkerName :: Nil) ++ interactor.map(i => i -> problem.interactorName)).map { lists =>
          new ProblemManifest(problem.testCount, problem.timeLimitMicros, problem.memoryLimit, problem.stdio, tester.basename, analyzeLists(lists), interactor.map(_.basename))
        }
    }

  def sanitizeAndStore =
    sanitize.flatMap(_ => storeProblem)
}

object Sanitizer extends Logging {
  private[this] val p7zFlags = "x" :: "-y" :: Nil

  private[this] def unpack(sandbox: Sandbox, problem: ProblemT, p7z: String): Future[RemoteFile] =
    sandbox.getExecutionParameters(p7z, p7zFlags ++ List("-o" + problem.destName, problem.zipName))
      .flatMap(sandbox.execute)
      .flatMap(_ => sandbox.stat(sandbox.sandboxId ** problem.destName :: Nil))
      .map(_.filter(_.isDir).headOption.getOrElse(throw new UnpackError))

  // No need to get problem file; it will be already there - or we fail after putGridfs
  private[this] def sanitize(sandbox: Sandbox, problem: ProblemDescription, p7z: String) = {
    sandbox.putGridfs(problem.archiveName, problem.zipName)
      .flatMap(_ => unpack(sandbox, problem, p7z))
      .flatMap(d => new ProblemSanitizer(sandbox, d, problem).sanitizeAndStore)
  }

  def apply(instance: CompilerInstance, problem: ProblemDescription) =
    sanitize(instance.comp, problem, instance.factory("zip").get.asInstanceOf[SevenzipHandler].p7z)
}

