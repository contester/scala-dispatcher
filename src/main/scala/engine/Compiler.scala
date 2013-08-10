package org.stingray.contester.engine

import org.stingray.contester.invokers.{InvokerInstance, Sandbox}
import org.stingray.contester.modules.SourceHandler
import com.twitter.util.Future
import org.stingray.contester.common._

object Compiler {
  def justCompile(sandbox: Sandbox, handler: SourceHandler, module: Module) =
    module.putToSandbox(sandbox, handler.sourceName)
      .flatMap(_ => handler.compile(sandbox))

  def apply(instance: InvokerInstance, module: Module, store: GridfsObjectStore, storeName: String): Future[(CompileResult, Option[Module])] =
    justCompile(instance.unrestricted, instance.factory(module.moduleType).asInstanceOf[SourceHandler], module)
      .flatMap {
      case (compileResult, compiledModuleOption) =>
        Future.collect(compiledModuleOption.map { compiledModule =>
          store.putModule(instance.unrestricted, storeName, instance.unrestricted.sandboxId / compiledModule.filename, compiledModule.moduleType)
        }.toSeq).map(compileResult -> _.headOption)
    }
}
