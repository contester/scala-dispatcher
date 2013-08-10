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
            .flatMap { compModule =>
            store.setMetaData(storeName) { meta =>
              meta.updated("sourceChecksum", module.moduleHash)
            }.map(_ => compModule)
          }
        }.toSeq).map(compileResult -> _.headOption)
    }

  def checkIfCompiled(module: Module, store: GridfsObjectStore, storeName: String): Future[Option[Module]] =
    store.getModuleEx(storeName).map(_.flatMap {
      case (module, metadata) =>
        if (ObjectStore.getMetadataString(metadata, "sourceChecksum") == module.moduleHash)
          Some(module)
        else
          None
    })
}
