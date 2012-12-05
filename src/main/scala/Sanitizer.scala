package org.stingray.contester

import ContesterImplicits._
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common.{ProblemManifest, ProblemDb}
import org.stingray.contester.polygon.Problem
import util.matching.Regex
import org.stingray.contester.invokers.{Sandbox, CompilerInstance}

class TesterNotFoundException extends scala.Throwable
class PdbStoreException(path: String) extends scala.Throwable(path)
class UnpackError extends scala.Throwable

class ProblemSanitizer(sandbox: Sandbox, base: RemoteFile, problem: Problem) extends Logging {
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
        new ProblemManifest(tester.basename, analyzeLists(lists), interactor.map(_.basename))
      }
    }

  def sanitizeAndStore(db: ProblemDb) =
    sanitize.flatMap(_ => storeProblem)
}

object Sanitizer extends Logging {
  private[this] val p7zFlags = "x" :: "-y" :: Nil

  private[this] def unpack(sandbox: Sandbox, problem: ProblemTuple, p7z: String): Future[RemoteFile] =
    sandbox.getExecutionParameters(p7z, p7zFlags ++ List("-o" + problem.destName, problem.zipName))
      .flatMap(sandbox.execute)
      .flatMap(_ => sandbox.stat(sandbox.sandboxId ** problem.destName :: Nil))
      .map(_.filter(_.isDir).headOption.getOrElse(throw new UnpackError))

  private[this] def sanitize(sandbox: Sandbox, problem: Problem, p7z: String, db: ProblemDb) = {
    db.getProblemFile(problem)
      .flatMap(_ => sandbox.putGridfs(problem.archiveName, problem.zipName))
      .flatMap(_ => unpack(sandbox, problem, p7z))
      .flatMap(d => new ProblemSanitizer(sandbox, d, problem).sanitizeAndStore(db))
  }

  def apply(instance: CompilerInstance, db: ProblemDb, problem: Problem) =
    sanitize(instance.comp, problem, instance.factory("zip").get.asInstanceOf[SevenzipHandler].p7z, db)
}
