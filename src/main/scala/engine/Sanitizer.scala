package org.stingray.contester.engine

import org.stingray.contester.invokers._
import grizzled.slf4j.Logging
import com.twitter.util.Future
import org.stingray.contester.problems.{ProblemManifest, ProblemID}
import org.stingray.contester.modules.SevenzipHandler
import org.stingray.contester.ContesterImplicits._
import scala.util.matching.Regex
import org.stingray.contester.common.Module

class TesterNotFoundException extends scala.Throwable
class PdbStoreException(path: String) extends scala.Throwable(path)
class UnpackError extends scala.Throwable
class SanitizerError extends Throwable
class ProblemFileNotFound extends SanitizerError

trait ProblemDescription extends ProblemID with EarliestTimeKey {
  def interactive: Boolean
  def stdio: Boolean
  def testCount: Int
  def timeLimitMicros: Long
  def memoryLimit: Long
}

class ProblemSanitizer(sandbox: Sandbox, base: RemoteFileName, problem: ProblemDescription) extends Logging {
  private[this] val testerRe = new Regex("^check\\.(\\S+)$")

  private[this] def detectGenerator =
    sandbox.invoker.api.glob(
      base / "doall.*" ::
      base / "Gen" / "doall.*" :: Nil, false)
      .map { g =>
      val m = g.isFile.map(v => v.ext.toLowerCase -> v).toMap
      m.get("cmd").orElse(m.get("bat")).orElse(m.values.headOption)
    }

  private[this] def useGenerator(gen: RemoteFileName) =
    sandbox.getExecutionParameters(gen.name, Nil)
      .map(_.setCurrentAndTemp(gen.parent.name(sandbox.invoker.api.pathSeparator)).setSanitizer)
      .flatMap(sandbox.execute(_)).map(trace(_)).map(_ => this)

  private[this] def sanitize =
    detectGenerator.flatMap(g => g.map(useGenerator(_)).getOrElse(Future.value(this)))

  private[this] def findTester =
    sandbox.invoker.api.glob(base / "*" :: Nil, false)
      .map { list =>
      trace(list.filter(_.isFile).map(_.basename.toLowerCase))
      val m = list.filter(_.isFile)
        .filter(x => testerRe.findFirstIn(x.basename.toLowerCase).nonEmpty)
        .map(x => x.ext.toLowerCase -> x).toMap
      m.get("exe").orElse(m.get("jar"))
    }.flatMap(_.map(Future.value(_)).getOrElse(Future.exception(new TesterNotFoundException)))

  private[this] def acceptableIds = Set("exe", "jar")

  private[this] def findInteractor =
    sandbox.invoker.api.glob(base / "files" / "interactor.*" :: Nil, false)
      .map(_.isFile.filter(x => acceptableIds(x.ext)).toSeq.sortBy(_.ext).headOption)

  private[this] def findInteractorOption =
    if (problem.interactive)
      findInteractor
    else Future.None


  private[this] val testBase = base / "tests"
  private[this] val tests = 1 to problem.testCount

  def statAndSave(sandbox: Sandbox, tester: InvokerRemoteFile, interactor: Option[InvokerRemoteFile]) = {
    val problemFiles = problemFileList(tester, interactor).map(x => x._1.name(sandbox.invoker.api.pathSeparator) -> x).toMap
    sandbox.invoker.api.stat(problemFiles.values.map(_._1), true).flatMap { filesToStore =>
      val shrunkFileList = filesToStore.map(x => problemFiles(x.name))
      sandbox.getGridfs(shrunkFileList).map { putResult =>
        val resultSet = putResult.map(x => problemFiles(x.name)).map(_._2).toSet
        val missing = ((((1 to problem.testCount).map(problem.inputName)) ++ List(problem.checkerName)).toSet -- resultSet)
        if (missing.nonEmpty)
          throw new PdbStoreException(missing.head)
        (1 to problem.testCount).filter(i => resultSet(problem.answerName(i)))
      }
    }
  }

  private[this] def problemFileList(tester: InvokerRemoteFile, interactor: Option[InvokerRemoteFile]): Iterable[(RemoteFileName, String, Option[String])] =
    tests.flatMap { i =>
      (testBase / "%02d".format(i), problem.inputName(i), None) ::
        (testBase / "%02d.a".format(i), problem.answerName(i), None) ::
      Nil
    } ++ interactor.map(intFile => (intFile, problem.interactorName, Some(Module.extractType(intFile.name)))) :+
      (tester, problem.checkerName, Some(Module.extractType(tester.name)))



  private[this] def storeProblem =
    findTester.join(findInteractorOption).flatMap {
      case (tester, interactor) =>
        statAndSave(sandbox, tester, interactor).map { answers =>
          new ProblemManifest(problem.testCount, problem.timeLimitMicros, problem.memoryLimit, problem.stdio, tester.basename, answers, interactor.map(_.basename))
        }
    }

  def sanitizeAndStore =
    sanitize.flatMap(_ => storeProblem)
}

object Sanitizer extends Logging {
  private[this] val p7zFlags = "x" :: "-y" :: Nil

  private[this] def unpack(sandbox: Sandbox, problem: ProblemID, p7z: String): Future[RemoteFileName] =
    sandbox.getExecutionParameters(p7z, p7zFlags ++ List("-o" + problem.destName, problem.zipName))
      .flatMap(sandbox.execute)
      .flatMap(_ => sandbox.stat(problem.destName, false))
      .map(_.filter(_.isDir).headOption.getOrElse(throw new UnpackError))

  // No need to get problem file; it will be already there - or we fail after putGridfs
  private[this] def sanitize(sandbox: Sandbox, problem: ProblemDescription, p7z: String) = {
    sandbox.putGridfs(problem.archiveName, problem.zipName)
      .flatMap(_ => unpack(sandbox, problem, p7z))
      .flatMap(d => new ProblemSanitizer(sandbox, d, problem).sanitizeAndStore)
  }

  def apply(instance: InvokerInstance, problem: ProblemDescription) =
    sanitize(instance.unrestricted, problem, instance.factory("zip").asInstanceOf[SevenzipHandler].p7z)
}

