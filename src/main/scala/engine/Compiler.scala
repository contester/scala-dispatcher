package org.stingray.contester.engine

import org.stingray.contester.invokers.{InvokerInstance, Sandbox}
import org.stingray.contester.modules.SourceHandler
import com.twitter.util.Future
import org.stingray.contester.common._

object Compiler {
  private def justCompile(sandbox: Sandbox, handler: SourceHandler, module: Module) =
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

  /**
   * Check if this module was already compiled. We use both checksum and storename.
   * TODO: Use memcached too.
   * @param module Source module. We compare module.moduleHash to (storeName).sourceChecksum.
   * @param store Object store.
   * @param storeName Name for compiled module.
   * @return Future(Some(compiled module) or None).
   */
  def checkIfCompiled(module: Module, store: GridfsObjectStore, storeName: String): Future[Option[Module]] =
    store.getModuleEx(storeName).map(_.flatMap {
      case (module, metadata) =>
        if (ObjectStore.getMetadataString(metadata, "sourceChecksum") == module.moduleHash)
          Some(module)
        else
          None
    })
}
