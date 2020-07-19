package org.stingray.contester.engine

import com.twitter.util.Future
import org.stingray.contester.common._
import org.stingray.contester.invokers.{InvokerInstance, Sandbox}
import org.stingray.contester.problems.TestLimits
import org.stingray.contester.proto.Blob
import org.stingray.contester.utils.SandboxUtil
import play.api.Logging

class CustomTestResult(val run: SingleRunResult, val output: Option[Blob])

object CustomTestLimits extends TestLimits {
  def memoryLimit: Long = 256 * 1024 * 1024
  def timeLimitMicros: Long = 5 * 1000 * 1000
}

object CustomTester extends Logging {

  private[this] val maxOutputSize = 64 * 1024 * 10
  private[this] val outputFileName = "output.txt"

  private def getOutput(sandbox: Sandbox, resultName: String): Future[Option[Blob]] =
    sandbox.stat(outputFileName, true)
      .map(_.find(_.size < maxOutputSize))
      .flatMap(_.map { remoteFile =>
      SandboxUtil.copyFromSandbox(sandbox, resultName, remoteFile, None).flatMap { _ =>
        sandbox.get(remoteFile).map(x => Some(x.getData))
      }
    }.getOrElse(Future.None))

  def apply(instance: InvokerInstance, module: Module, input: Array[Byte], resultName: String): Future[CustomTestResult] = {
    val moduleHandler = instance.factory.getBinary(module.moduleType).get
      instance.restricted.put(Blobs.storeBinary(input), "input.txt")
        .flatMap{ _ => Tester.executeSolution(instance.restricted, moduleHandler, module, CustomTestLimits, true) }
        .flatMap { solutionResult =>
        (if (solutionResult.success)
          getOutput(instance.restricted, resultName)
        else Future.None).map(v => new CustomTestResult(solutionResult, v))
      }
    }
}

