package org.stingray.contester.engine

import org.stingray.contester.common.{GridfsObjectStore, Module, Blobs, SingleRunResult}
import org.stingray.contester.proto.Blobs.Blob
import org.stingray.contester.problems.TestLimits
import grizzled.slf4j.Logging
import org.stingray.contester.invokers.{InvokerInstance, Sandbox}
import com.twitter.util.Future
import org.stingray.contester.modules.BinaryHandler

class CustomTestResult(val run: SingleRunResult, val output: Option[Blob])

object CustomTestLimits extends TestLimits {
  def memoryLimit: Long = 256 * 1024 * 1024
  def timeLimitMicros: Long = 5 * 1000 * 1000
}

object CustomTester extends Logging {

  private[this] val maxOutputSize = 64 * 1024 * 1024
  private[this] val outputFileName = "output.txt"

  private def getOutput(sandbox: Sandbox, store: GridfsObjectStore, resultName: String): Future[Option[Blob]] =
    sandbox.stat(outputFileName, true)
      .map(_.filter(_.size < maxOutputSize).headOption)
      .flatMap(_.map { remoteFile =>
      store.copyFromSandbox(sandbox, resultName, remoteFile, Map.empty[String, Any]).flatMap { _ =>
        sandbox.get(remoteFile).map(x => Some(x.getData))
      }
    }.getOrElse(Future.None))

  def apply(instance: InvokerInstance, module: Module, input: Array[Byte], store: GridfsObjectStore, resultName: String): Future[CustomTestResult] = {
    val moduleHandler = instance.factory(module.moduleType).asInstanceOf[BinaryHandler]
      instance.restricted.put(Blobs.storeBinary(input), "input.txt")
        .flatMap{ _ => Tester.executeSolution(instance.restricted, moduleHandler, module, CustomTestLimits, false) }
        .flatMap { solutionResult =>
        (if (solutionResult.success)
          getOutput(instance.restricted, store, resultName)
        else Future.None).map(v => new CustomTestResult(solutionResult, v))
      }
    }
}

