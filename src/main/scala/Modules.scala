package org.stingray.contester

import ContesterImplicits._
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common._
import org.stingray.contester.modules.{LinuxHandlers, Win32Handlers}
import org.stingray.contester.proto.Local.LocalExecutionParameters

trait ModuleHandler {
  def moduleTypes: Iterable[String]
  def internal: Boolean = false
}

trait SourceHandler extends ModuleHandler {
  def sourceName: String

  def compile(sandbox: Sandbox): Future[CompileResult]
  def filter(params: LocalExecutionParameters) = params

  def step(stepName: String, sandbox: Sandbox, applicationName: String, arguments: ExecutionArguments): Future[StepResult] = {
    sandbox.getExecutionParameters(applicationName, arguments)
      .map(_.setCompiler).map(filter(_))
      .flatMap(sandbox.executeWithParams).map(x => StepResult(stepName, x._1, x._2))
  }

  def compileAndCheck(sandbox: Sandbox, applicationName: String, arguments: ExecutionArguments, resultName: String) =
    step("Compilation", sandbox, applicationName, arguments)
      .flatMap { result =>
      sandbox.statFile(resultName).map(!_.isEmpty).map((result, _))
    }

  def compile0(sandbox: Sandbox, applicationName: String, arguments: ExecutionArguments,
                    compileResult: String, resultType: Option[String]) =
    compileAndCheck(sandbox, applicationName, arguments, compileResult)
      .flatMap {
      case (stepResult, isFile) =>
          SourceHandler.makeCompileResult(Seq(stepResult), sandbox, compileResult, resultType)
    }
}

class SevenzipHandler(val p7z: String) extends ModuleHandler {
  val moduleTypes = "zip" :: Nil
}

object SourceHandler {
  def makeCompileResult(steps: Seq[StepResult], sandbox: Sandbox, filename: String, moduleType: Option[String]) =
    (if (steps.last.success) {
      sandbox.getModuleOption(sandbox.sandboxId ** filename).map(_.map(_.setType(moduleType)))
    } else Future.value(None)).map(CompileResult(steps, _))
  }


trait ModuleFactory {
  def apply(moduleType: String): Option[ModuleHandler]
  def moduleTypes: Seq[String]

  def getSource(moduleType: String): SourceHandler =
    apply(moduleType).get.asInstanceOf[SourceHandler]

  def getBinary(moduleType: String): BinaryHandler =
    apply(moduleType).get.asInstanceOf[BinaryHandler]

  def get7z: SevenzipHandler =
    apply("zip").get.asInstanceOf[SevenzipHandler]
}

class SimpleModuleFactory(handlers: Iterable[ModuleHandler]) extends ModuleFactory {
  val moduleMap = handlers.flatMap(x => x.moduleTypes.map(y => y -> x)).toMap

  def apply(moduleType: String) =
    moduleMap.get(moduleType)

  def moduleTypes =
    moduleMap.filter(!_._2.internal).keys.toSeq
}

object ModuleFactoryFactory extends Logging {
  def getHandlers(inv: InvokerId) = inv.platform match {
    case "win32" => new Win32Handlers(inv).apply
    case "linux" => new LinuxHandlers(inv).apply
    case _ => Future.value(Seq())
  }

  def apply(inv: InvokerId): Future[ModuleFactory] = {
    info("Testing invoker " + inv)
    getHandlers(inv).map(new SimpleModuleFactory(_))
  }
}

trait BinaryHandler extends ModuleHandler {
  def binaryExt: String
  def solutionName: String

  def getTesterParameters(sandbox: Sandbox, name: String, arguments: List[String]): Future[LocalExecutionParameters]
  def getSolutionParameters(sandbox: Sandbox, name: String, test: TestLimits): Future[LocalExecutionParameters]
}

trait SimpleCompileHandler extends SourceHandler {
  def compiler: String
  def flags: ExecutionArguments
  def binary: String
  def resultType: Option[String] = None

  def compile(sandbox: Sandbox): Future[CompileResult] =
    compile0(sandbox, compiler, flags, binary, resultType)
}


