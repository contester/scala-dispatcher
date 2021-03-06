package org.stingray.contester.engine

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.ContesterImplicits._
import org.stingray.contester.common.Module
import org.stingray.contester.engine.Sanitizer.ProblemItself
import org.stingray.contester.invokers._
import org.stingray.contester.problems._

import scala.util.matching.Regex

class TesterNotFoundException extends scala.Throwable
class PdbStoreException(path: String) extends scala.Throwable(path)
class UnpackError extends scala.Throwable
class SanitizerError extends Throwable
class ProblemFileNotFound extends SanitizerError

trait ProblemDescription extends EarliestTimeKey {
  def interactive: Boolean
  def stdio: Boolean
  def testCount: Int
  def timeLimitMicros: Long
  def memoryLimit: Long
}

class ProblemSanitizer(sandbox: Sandbox, base: RemoteFileName, problem: ProblemItself, problemAssets: ProblemAssetInterface) extends Logging {
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
      .flatMap(sandbox.execute).map(trace(_)).map(_ => this)

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
    }.flatMap(_.map(Future.value).getOrElse(Future.exception(new TesterNotFoundException)))

  private[this] def acceptableIds = Set("exe", "jar")

  private[this] def findInteractor =
    sandbox.invoker.api.glob(base / "files" / "interactor.*" :: Nil, false)
      .map(_.isFile.filter(x => acceptableIds(x.ext)).toSeq.sortBy(_.ext).headOption)

  private[this] def findInteractorOption =
    if (problem.interactive)
      findInteractor
    else Future.None


  private[this] val testBase = base / "tests"

  def statAndSave(sandbox: Sandbox, tester: InvokerRemoteFile, interactor: Option[InvokerRemoteFile]) = {
    val problemFiles = problemFileList(tester, interactor).map(x => x._1.name(sandbox.invoker.api.pathSeparator) -> x).toMap
    sandbox.invoker.api.stat(problemFiles.values.map(_._1), true).flatMap { filesToStore =>
      val shrunkFileList = filesToStore.map(x => problemFiles(x.name)).toSeq
      sandbox.getGridfs(shrunkFileList).map { putResult =>
        val resultSet = putResult.map(x => problemFiles(x.name)).map(_._2).toSet
        val missing = ((((1 to problem.testCount).map(x => problemAssets.inputName(x))) ++ List(problemAssets.checkerName)).toSet -- resultSet)
        if (missing.nonEmpty) {
          throw new PdbStoreException(missing.head)
	}
        (1 to problem.testCount).filter(i => resultSet(problemAssets.answerName(i)))
      }
    }
  }

  private[this] def problemFileList(tester: InvokerRemoteFile, interactor: Option[InvokerRemoteFile]): Iterable[(RemoteFileName, String, Option[String])] =
    (1 to problem.testCount).flatMap { i =>
      (testBase / "%02d".format(i), problemAssets.inputName(i), None) ::
        (testBase / "%02d.a".format(i), problemAssets.answerName(i), None) ::
      Nil
    } ++ interactor.map(intFile => (intFile, problemAssets.interactorName, Some(Module.extractType(intFile.name)))) :+
      (tester, problemAssets.checkerName, Some(Module.extractType(tester.name)))



  private[this] def storeProblem =
    findTester.join(findInteractorOption).flatMap {
      case (tester, interactor) =>
        statAndSave(sandbox, tester, interactor).map { answers =>
          SimpleProblemManifest(problem.handle, problem.revision.toInt, problem.testCount, problem.timeLimitMicros,
            problem.memoryLimit, problem.stdio, tester.basename, answers.toSet, interactor.map(_.basename))
        }
    }

  def sanitizeAndStore =
    sanitize.flatMap(_ => storeProblem)
}

object Sanitizer extends Logging {
  type ProblemItself = ProblemHandleWithRevision with ProblemDescription
  type ProblemAssets = ProblemArchiveInterface with ProblemAssetInterface

  private[this] val p7zFlags = "x" :: "-y" :: Nil

  private[this] val destName = "p"
  private[this] val zipName = "p.zip"

  private[this] def unpack(sandbox: Sandbox, p7z: String): Future[RemoteFileName] =
    sandbox.getExecutionParameters(p7z, p7zFlags ++ List(s"-o${destName}", zipName))
      .flatMap(sandbox.execute)
      .flatMap(_ => sandbox.stat(destName, false))
      .map(_.find(_.isDir).getOrElse(throw new UnpackError))

  // No need to get problem file; it will be already there - or we fail after putGridfs
  private[this] def sanitize(sandbox: Sandbox, problem: ProblemItself, problemAssets: ProblemAssets, p7z: String) = {
    sandbox.putGridfs(problemAssets.archiveName, zipName)
      .flatMap(_ => unpack(sandbox, p7z))
      .flatMap(d => new ProblemSanitizer(sandbox, d, problem, problemAssets).sanitizeAndStore)
  }

  /**
   * Sanitize a given problem.
 *
   * @param instance Invoker instance to use.
   * @param problem Problem to sanitize. Problem archive already needs to be loaded into gridfs.
   * @return
   */
  def apply(instance: InvokerInstance, problem: ProblemItself, problemAssets: ProblemAssets) =
    sanitize(instance.unrestricted, problem, problemAssets, instance.factory.get7z.get.p7z)
}

