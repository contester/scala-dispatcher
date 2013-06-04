package org.stingray.contester.engine

import grizzled.slf4j.Logging
import org.stingray.contester.proto.Local.{LocalExecutionResult, LocalExecutionParameters}
import org.stingray.contester.common._
import org.stingray.contester.invokers._
import org.stingray.contester.modules.BinaryHandler
import org.stingray.contester.proto.Blobs.Module
import org.stingray.contester.problems.{Test, TestLimits}
import org.apache.commons.io.FilenameUtils
import com.twitter.util.{Duration, Future}
import org.stingray.contester.ContesterImplicits._
import org.stingray.contester.utils.Utils
import java.util.concurrent.TimeUnit
import org.stingray.contester.rpc4.RemoteError
import scala.Some

object Tester extends Logging {
  private def asRunResult(x: (LocalExecutionParameters, LocalExecutionResult), isJava: Boolean) =
    if (isJava)
      JavaRunResult(x._1, x._2)
    else
      SingleRunResult(x._1, x._2)

  private def asTesterRunResult(x: (LocalExecutionParameters, LocalExecutionResult)) =
    TesterRunResult(x._1, x._2)

  def executeSolution(sandbox: Sandbox, handler: BinaryHandler, module: Module, testLimits: TestLimits, stdio: Boolean) =
    sandbox.put(module, handler.solutionName)
      .flatMap(_ => handler.getSolutionParameters(sandbox, handler.solutionName, testLimits))
      .map(_.emulateStdioIf(stdio, sandbox))
      .flatMap(sandbox.executeWithParams(_).handle {
      case e: RemoteError => throw new TransientError(e)
    }).map(asRunResult(_, module.getType == "jar"))

  private def executeTester(sandbox: Sandbox, handler: BinaryHandler, name: String) =
    handler.getTesterParameters(sandbox, name, "input.txt" :: "output.txt" :: "answer.txt" :: Nil)
      .map(_.setTester)
      .flatMap(sandbox.executeWithParams(_).handle {
      case e: RemoteError => throw new TransientError(e)
    }).map(asTesterRunResult(_))

  private def runInteractive(instance: InvokerInstance, handler: BinaryHandler, moduleType: String, test: Test) =
    test.prepareInteractorBinary(instance.comp).flatMap { interactorName =>
      val testerHandler = instance.factory.getBinary(FilenameUtils.getExtension(interactorName))
      test.prepareInput(instance.comp).flatMap(_ => test.prepareTester(instance.comp))
        .flatMap(_ => handler.getSolutionParameters(instance.run, handler.solutionName, test.getLimits(moduleType)).join(testerHandler.getTesterParameters(instance.comp, interactorName, "input.txt" :: "output.txt" :: "answer.txt" :: Nil).map(_.setTester)))
        .flatMap {
        case (secondp, firstp) => instance.invoker.i.executeConnected(firstp, secondp)
          .map {
          case (firstr, secondr) => new InteractiveRunResult(SingleRunResult(firstp, firstr), asRunResult((secondp, secondr), moduleType == "jar"))
        }
      }
    }

  private def testInteractive(instance: InvokerInstance, module: Module, test: Test): Future[(RunResult, Option[TesterRunResult])] = {
    val moduleHandler = instance.factory.getBinary(module.getType)
    instance.run.put(module, moduleHandler.solutionName).flatMap { _ =>
      runInteractive(instance, moduleHandler, module.getType, test)}.flatMap { runResult =>
      if (runResult.success) {
        test.prepareTesterBinary(instance.comp).flatMap { testerName =>
          executeTester(instance.comp, instance.factory.getBinary(FilenameUtils.getExtension(testerName)), testerName)
            .map { testerResult =>
            (runResult, Some(testerResult))
          }
        }
      } else Future.value((runResult, None))
    }
  }

  def apply(instance: InvokerInstance, module: Module, test: Test): Future[TestResult] =
    (if (test.interactive)
      testInteractive(instance, module, test)
    else
      testOld(instance, module, test)).map(x => new TestResult(x._1, x._2))

  private def sandboxAfterExecutionResult(stats: Iterable[RemoteFile]) = {
    val m = stats.map(x => x.name -> x).toMap
    trace("After execution result, we have: %s".format(m.mapValues(x => if (x.hasSha1) Blobs.bytesToString(x.getSha1) else "")))
    stats
  }


  def testOld(instance: RunnerInstance, module: Module, test: Test): Future[(RunResult, Option[TesterRunResult])] = {
    val moduleHandler = instance.factory.getBinary(module.getType)
    test.prepareInput(instance.run)
      .flatMap { _ => executeSolution(instance.run, moduleHandler, module, test.getLimits(module.getType), test.stdio) }
      .flatMap { solutionResult =>
      if (solutionResult.success) {
        instance.run.statAll
          .map(sandboxAfterExecutionResult(_))
          .flatMap { _ =>
            test.prepareInput(instance.run).flatMap { _ => test.prepareTester(instance.run)}
            .flatMap(_ => test.prepareTesterBinary(instance.run))
            .flatMap { testerName =>
              Utils.later(Duration(500, TimeUnit.MILLISECONDS))
                .flatMap(_ => instance.run.statAll)
                .flatMap { nstats =>
                  executeTester(instance.run, instance.factory.getBinary(FilenameUtils.getExtension(testerName)), testerName)
                }
          }.map { testerResult =>
            (solutionResult, Some(testerResult))
          }
        }
      } else Future.value((solutionResult, None))
    }
  }
}

