package org.stingray.contester.engine

import org.stingray.contester.invokers.{InvokerInstance, Sandbox}
import org.stingray.contester.modules.{CompiledModule, SourceHandler}
import com.twitter.util.Future
import org.stingray.contester.common._
import grizzled.slf4j.Logging
import scala.concurrent.ExecutionContext.Implicits.global

object Compiler extends Logging {
  private def justCompile(sandbox: Sandbox, handler: SourceHandler, module: Module) = 
    module.putToSandbox(sandbox, handler.sourceName).flatMap { _ =>
      handler.compile(sandbox)
    }

  private def storeCompiledModule(sandbox: Sandbox, store: GridfsObjectStore, stored: HasGridfsPath, sourceHash: String,
                                  module: CompiledModule): Future[Option[Module]] =
    store.putModule(sandbox, stored.toGridfsPath, sandbox.sandboxId / module.filename, module.moduleType)

  def apply(instance: InvokerInstance, module: Module, store: GridfsObjectStore, stored: HasGridfsPath): Future[(CompileResult, Option[Module])] =
    justCompile(instance.unrestricted, instance.factory(module.moduleType).asInstanceOf[SourceHandler], module)
      .flatMap {
      case (compileResult, compiledModuleOption) =>
        compiledModuleOption.map(storeCompiledModule(instance.unrestricted, store, stored, module.moduleHash, _))
            .getOrElse(Future.None)
            .map(compileResult -> _)
    }
}
