package org.stingray.contester

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common.{Blobs, SingleRunResult}
import proto.Blobs.{Blob, Module}
import org.stingray.contester.invokers.RunnerInstance

class CustomTestResult(val run: SingleRunResult, val output: Option[Blob])

object CustomTestLimits extends TestLimits {
  def memoryLimit: Long = 256 * 1024 * 1024
  def timeLimitMicros: Long = 5 * 1000 * 1000
}

object CustomTester extends Logging {

  private[this] val maxOutputSize = 64 * 1024 * 1024
  private[this] val outputFileName = "output.txt"

  private def getOutput(sandbox: Sandbox): Future[Option[Blob]] =
    sandbox.statFile(outputFileName)
      .map(_.filter(_.size < maxOutputSize).headOption)
      .flatMap(_.map(sandbox.getModuleOption(_)).getOrElse(Future.None))
      .map(_.map(_.getData))

  def apply(instance: RunnerInstance, module: Module, input: Array[Byte]): Future[CustomTestResult] =
    instance.factory(module.getType).collect {
      case binary: BinaryHandler => binary
    }.map { moduleHandler =>
      instance.run.put(Blobs.storeBinary(input), "input.txt")
        .flatMap{ _ => Tester.executeSolution(instance.run, moduleHandler, module, CustomTestLimits, false) }
        .flatMap { solutionResult =>
        (if (solutionResult.success)
          getOutput(instance.run)
        else Future.None).map(v => new CustomTestResult(solutionResult, v))
      }
    }.get
}
