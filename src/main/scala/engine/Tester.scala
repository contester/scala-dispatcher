package org.stingray.contester.engine

import grizzled.slf4j.Logging
import org.stingray.contester.proto.Local.{LocalExecutionParameters, LocalExecutionResult}
import org.stingray.contester.common._
import org.stingray.contester.invokers._
import org.stingray.contester.modules.BinaryHandler
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
    module.putToSandbox(sandbox, handler.solutionName)
      .flatMap(_ => handler.getSolutionParameters(sandbox, handler.solutionName, testLimits))
      .map(_.emulateStdioIf(stdio, sandbox))
      .flatMap(sandbox.executeWithParams(_).handle {
      case e: RemoteError => throw new TransientError(e)
    }).map(asRunResult(_, module.moduleType == "jar"))

  private def executeTester(sandbox: Sandbox, handler: BinaryHandler, name: String) =
    handler.getTesterParameters(sandbox, name, "input.txt" :: "output.txt" :: "answer.txt" :: Nil)
      .map(_.setTester)
      .flatMap(sandbox.executeWithParams(_).handle {
      case e: RemoteError => throw new TransientError(e)
    }).map(asTesterRunResult(_))

  private def runInteractive(instance: InvokerInstance, handler: BinaryHandler, moduleType: String, test: Test) =
    test.prepareInteractorBinary(instance.unrestricted).flatMap { interactorName =>
      val testerHandler = instance.factory(FilenameUtils.getExtension(interactorName)).asInstanceOf[BinaryHandler]
      test.prepareInput(instance.unrestricted).flatMap(_ => test.prepareTester(instance.unrestricted))
        .flatMap(_ => handler.getSolutionParameters(instance.restricted, handler.solutionName, test.getLimits(moduleType)).join(testerHandler.getTesterParameters(instance.unrestricted, interactorName, "input.txt" :: "output.txt" :: "answer.txt" :: Nil).map(_.setTester)))
        .flatMap {
        case (secondp, firstp) => instance.invoker.api.executeConnected(firstp, secondp)
          .map {
          case (firstr, secondr) => new InteractiveRunResult(SingleRunResult(firstp, firstr), asRunResult((secondp, secondr), moduleType == "jar"))
        }
      }
    }

  private def testInteractive(instance: InvokerInstance, module: Module, test: Test): Future[(RunResult, Option[TesterRunResult])] = {
    val moduleHandler = instance.factory(module.moduleType).asInstanceOf[BinaryHandler]
    module.putToSandbox(instance.restricted, moduleHandler.solutionName).flatMap { _ =>
      runInteractive(instance, moduleHandler, module.moduleType, test)}.flatMap { runResult =>
      if (runResult.success) {
        test.prepareTesterBinary(instance.unrestricted).flatMap { testerName =>
          executeTester(instance.unrestricted, instance.factory(FilenameUtils.getExtension(testerName)).asInstanceOf[BinaryHandler], testerName)
            .map { testerResult =>
            (runResult, Some(testerResult))
          }
        }
      } else Future.value((runResult, None))
    }
  }

  def apply(instance: InvokerInstance, module: Module, test: Test, store: GridfsObjectStore, resultName: String): Future[TestResult] =
    (if (test.interactive)
      testInteractive(instance, module, test)
    else
      testOld(instance, module, test, store, resultName)).map(x => new TestResult(x._1, x._2))

  private def testOld(instance: InvokerInstance, module: Module, test: Test, store: GridfsObjectStore, resultName: String): Future[(RunResult, Option[TesterRunResult])] = {
    val moduleHandler = instance.factory(module.moduleType).asInstanceOf[BinaryHandler]
    test.prepareInput(instance.restricted)
      .flatMap { _ => executeSolution(instance.restricted, moduleHandler, module, test.getLimits(module.moduleType), test.stdio) }
      .flatMap { solutionResult =>
        if (solutionResult.success) {
            store.copyFromSandbox(instance.restricted, resultName, instance.unrestricted.sandboxId / "output.txt", Map.empty)
            .flatMap { _ => test.prepareInput(instance.restricted)}.flatMap { _ => test.prepareTester(instance.restricted)}
            .flatMap(_ => test.prepareTesterBinary(instance.restricted))
            .flatMap { testerName =>
              Utils.later(Duration(500, TimeUnit.MILLISECONDS))
                .flatMap { _ =>
                  executeTester(instance.restricted, instance.factory(FilenameUtils.getExtension(testerName)).asInstanceOf[BinaryHandler], testerName)
                }
          }.map { testerResult =>
            (solutionResult, Some(testerResult))
          }
        } else Future.value((solutionResult, None))
    }
  }
}

