package org.stingray.contester.engine

import org.stingray.contester.invokers.{InvokerInstance, Sandbox}
import org.stingray.contester.modules.{CompileResultAndModule, CompiledModuleHandle, SourceHandler}
import com.twitter.util.Future
import org.stingray.contester.common._
import grizzled.slf4j.Logging
import org.stingray.contester.utils.SandboxUtil

import scala.concurrent.ExecutionContext.Implicits.global

object Compiler extends Logging {
  private def justCompile(sandbox: Sandbox, handler: SourceHandler, module: Module) = 
    module.putToSandbox(sandbox, handler.sourceName).flatMap { _ =>
      handler.compile(sandbox)
    }

  private def storeCompiledModule(sandbox: Sandbox, stored: String, sourceHash: String,
                                  module: CompiledModuleHandle): Future[Option[Module]] = {
    SandboxUtil.putModule(sandbox, stored, sandbox.sandboxId / module.filename, module.moduleType)
      .onFailure(error(s"storeCompiledModule($stored)", _))
  }

  def apply(instance: InvokerInstance, module: Module, stored: String): Future[(CompileResult, Option[Module])] =
    justCompile(instance.unrestricted, instance.factory.getSource(module.moduleType).get, module)
      .flatMap {
      case CompileResultAndModule(compileResult, compiledModuleOption) =>
        compiledModuleOption.map(storeCompiledModule(instance.unrestricted, stored, module.moduleHash, _))
            .getOrElse(Future.None)
            .map(compileResult -> _)
    }
}
