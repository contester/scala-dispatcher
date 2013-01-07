package org.stingray.contester.engine

import grizzled.slf4j.Logging
import org.stingray.contester.proto.Local.{LocalExecutionResult, LocalExecutionParameters}
import org.stingray.contester.common._
import org.stingray.contester.invokers.{TransientError, RunnerInstance, InvokerInstance, Sandbox}
import org.stingray.contester.modules.BinaryHandler
import org.stingray.contester.proto.Blobs.Module
import org.stingray.contester.problems.{Test, TestLimits}
import org.apache.commons.io.FilenameUtils
import com.twitter.util.{Duration, Future}
import org.stingray.contester.ContesterImplicits._
import org.stingray.contester.utils.Utils
import java.util.concurrent.TimeUnit
import org.stingray.contester.rpc4.RemoteError

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

  def executeTester(sandbox: Sandbox, handler: BinaryHandler, name: String) =
    handler.getTesterParameters(sandbox, name, "input.txt" :: "output.txt" :: "answer.txt" :: Nil)
      .map(_.setTester)
      .flatMap(sandbox.executeWithParams(_).handle {
      case e: RemoteError => throw new TransientError(e)
    }).map(asTesterRunResult(_))

  def runInteractive(instance: InvokerInstance, handler: BinaryHandler, moduleType: String, test: Test) =
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

  def testInteractive(instance: InvokerInstance, module: Module, test: Test): Future[(RunResult, Option[TesterRunResult])] = {
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

  def apply(instance: InvokerInstance, module: Module, test: Test): Future[(RunResult, Option[TesterRunResult])] =
    if (test.interactive)
      testInteractive(instance, module, test)
    else
      testOld(instance, module, test)


  def testOld(instance: RunnerInstance, module: Module, test: Test): Future[(RunResult, Option[TesterRunResult])] = {
    val moduleHandler = instance.factory.getBinary(module.getType)
    test.prepareInput(instance.run)
      .flatMap { _ => executeSolution(instance.run, moduleHandler, module, test.getLimits(module.getType), test.stdio) }
      .flatMap { solutionResult =>
      if (solutionResult.success) {
        instance.run.glob("*").flatMap { stats =>
          trace(stats)
          test.prepareInput(instance.run).flatMap{_ => test.prepareTester(instance.run)}
          .flatMap(_ => test.prepareTesterBinary(instance.run))
          .flatMap { testerName =>
            Utils.later(Duration(500, TimeUnit.MILLISECONDS)).flatMap(_ => instance.run.glob("*")).flatMap { nstats =>
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

