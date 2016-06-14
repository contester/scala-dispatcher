package org.stingray.contester.modules

import com.twitter.util.Future
import org.stingray.contester.common._
import org.stingray.contester.proto.LocalExecutionParameters
import org.stingray.contester.utils.ExecutionArguments
import org.stingray.contester.invokers.{InvokerAPI, Sandbox}
import org.stingray.contester.ContesterImplicits._
import org.stingray.contester.problems.TestLimits

trait ModuleHandler {
  def moduleTypes: Iterable[String]
}

/**
 * In a new module factory system, this is a factory class to produce module handlers.
 *
 * @tparam A ModuleHandler subtype.
 */
trait ModuleType[A <: ModuleHandler] {
  def apply(invoker: InvokerAPI): Future[A]
}

case class CompiledModuleHandle(filename: String, moduleType: String)

case class CompileResultAndModule(result: CompileResult, module: Option[CompiledModuleHandle])

trait SourceHandler extends ModuleHandler {
  /**
   * Source name for the module. Used to put it there.
 *
   * @return Source name, with extension.
   */
  def sourceName: String

  /**
   * Compile module which is already in the sandbox.
 *
   * @param sandbox Sandbox to use for compilation.
   * @return Result and file name, if any.
   */
  def compile(sandbox: Sandbox): Future[CompileResultAndModule]
}

trait BinaryHandler extends ModuleHandler {
  def solutionName: String

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]): Future[LocalExecutionParameters]
  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits): Future[LocalExecutionParameters]
}

class SevenzipHandler(val p7z: String) extends ModuleHandler {
  val moduleTypes = "zip" :: Nil
}

object SourceHandler {
  def step(stepName: String, sandbox: Sandbox, applicationName: String,
           arguments: ExecutionArguments): Future[StepResult] = {
    sandbox.getExecutionParameters(applicationName, arguments)
      .map(_.setCompiler)
      .flatMap(sandbox.executeWithParams).map { x =>
      StepResult(stepName, x._1, x._2)
    }
  }

  def checkForFile(sandbox: Sandbox, filename: String): Future[Boolean] =
    sandbox.stat(filename, false).map(_.isFile.nonEmpty)

  def stepAndCheck(stepName: String, sandbox: Sandbox, applicationName: String, arguments: ExecutionArguments, resultName: String): Future[(StepResult, Boolean)] =
    step(stepName, sandbox, applicationName, arguments).flatMap { stepResult =>
      checkForFile(sandbox, resultName).map { checkResult =>
        (stepResult, checkResult)
      }
    }

  def makeCompileResultAndModule(steps: Seq[StepResult], success: Boolean, resultName: String, resultType: String): CompileResultAndModule =
    CompileResultAndModule(new RealCompileResult(steps, success),
      if (success) Some(CompiledModuleHandle(resultName, resultType)) else None)
}

trait SimpleCompileHandler extends SourceHandler {
  def compiler: String
  def flags: ExecutionArguments
  def binary: String
  def binaryExt: String

  def compile(sandbox: Sandbox): Future[CompileResultAndModule] = {
    SourceHandler.stepAndCheck("Compilation", sandbox, compiler, flags, binary).map {
      case (stepResult, success) =>
        SourceHandler.makeCompileResultAndModule(Seq(stepResult), success, binary, binaryExt)
    }
  }
}


