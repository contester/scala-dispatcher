package org.stingray.contester.engine

import grizzled.slf4j.Logging
import org.stingray.contester.common._
import org.stingray.contester.invokers._
import org.stingray.contester.modules.{BinaryHandler, ModuleHandler}
import org.stingray.contester.problems.{Test, TestLimits}
import org.apache.commons.io.FilenameUtils
import com.twitter.util.{Duration, Future}
import org.stingray.contester.ContesterImplicits._
import org.stingray.contester.utils.{ProtobufTools, SandboxUtil, Utils}
import java.util.concurrent.TimeUnit

import org.stingray.contester.rpc4.RemoteError

import scala.Some
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers, WrappedChannelBuffer}
import com.google.protobuf.Message
import org.stingray.contester.proto.{LocalExecution, LocalExecutionParameters, LocalExecutionResult}

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

  private def storeFile(sandbox: Sandbox, storeAs: HasGridfsPath, storeWhat: RemoteFileName): Future[Option[String]] =
    sandbox.invoker.api.stat(Seq(storeWhat), true)
      .map(_.headOption).flatMap(_.map(_ => SandboxUtil.copyFromSandbox(sandbox, storeAs.toGridfsPath, storeWhat, None)).getOrElse(Future.None))

  def apply(instance: InvokerInstance, module: Module, test: Test,
            resultName: HasGridfsPath, objectCache: ObjectCache): Future[TestResult] =
    (if (test.interactive)
      testInteractive(instance, module, test)
    else
      testOld(instance, module, test, resultName, objectCache)).map(x => new TestResult(x._1, x._2))

  /*
    We can cache the entire result on (module, testKey) here
    We can cache the sha1 of the output here.
   */

  private def prepareAndRunTester(sandbox: Sandbox, factory: (String) => ModuleHandler, test: Test): Future[TesterRunResult] =
    test.prepareInput(sandbox)
      .flatMap { _ => test.prepareTester(sandbox)}
      .flatMap { _ => test.prepareTesterBinary(sandbox)}
      .flatMap { testerName =>
      Utils.later(Duration(500, TimeUnit.MILLISECONDS))
        .flatMap { _ =>
        executeTester(sandbox, factory(FilenameUtils.getExtension(testerName)).asInstanceOf[BinaryHandler], testerName)
      }
    }

  private def executeAndStoreSuccess(sandbox: Sandbox, factory: (String) => ModuleHandler,
                                     test: Test, module: Module, resultName: HasGridfsPath,
                                     cache: ObjectCache): Future[(RunResult, Option[String])] =
    test.prepareInput(sandbox)
      .flatMap { _ =>
      executeSolution(sandbox, factory(module.moduleType).asInstanceOf[BinaryHandler],
        module, test.getLimits(module.moduleType), test.stdio) }
      .flatMap { solutionResult =>
    { if (solutionResult.success) {
          storeFile(sandbox, resultName, sandbox.sandboxId / "output.txt")
        } else {
          Future.None
        }}.map((solutionResult, _))
    }

  // TODO: restore caching of test results. Use ScalaCache and better keys (not just outputHash)

  private def testOld(instance: InvokerInstance, module: Module, test: Test,
                      resultName: HasGridfsPath, objectCache: ObjectCache): Future[(RunResult, Option[TesterRunResult])] =
    executeAndStoreSuccess(instance.restricted, instance.factory, test, module, resultName, objectCache)
      .flatMap {
      case (solutionResult, optHash) =>
        optHash.map { outputHash =>
          test.key.flatMap { testKey =>
            prepareAndRunTester(instance.restricted, instance.factory, test)
                .map { testerResult =>
              (solutionResult, Some(testerResult))
            }
          }
        }.getOrElse(Future.value(solutionResult, None))
    }
}

