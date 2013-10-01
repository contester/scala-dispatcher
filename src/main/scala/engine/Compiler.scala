package org.stingray.contester.engine

import org.stingray.contester.invokers.{InvokerInstance, Sandbox}
import org.stingray.contester.modules.{CompiledModule, SourceHandler}
import com.twitter.util.Future
import org.stingray.contester.common._
import grizzled.slf4j.Logging

object Compiler extends Logging {
  private def justCompile(sandbox: Sandbox, handler: SourceHandler, module: Module) =
    module.putToSandbox(sandbox, handler.sourceName)
      .flatMap(_ => handler.compile(sandbox))

  private def storeCompiledModule(sandbox: Sandbox, store: GridfsObjectStore, storeName: String, sourceHash: String, optModule: Option[CompiledModule]) =
    optModule.map { compiledModule =>
      store.putModule(sandbox, storeName, sandbox.sandboxId / compiledModule.filename, compiledModule.moduleType)
        .flatMap { result =>
        store.setMetaData(storeName) { meta =>
          meta.updated("sourceChecksum", sourceHash)
        }.map(_ => Some(result))
      }
    }.getOrElse(Future.None)

  def apply(instance: InvokerInstance, module: Module, store: GridfsObjectStore, storeName: String): Future[(CompileResult, Option[Module])] =
    justCompile(instance.unrestricted, instance.factory(module.moduleType).asInstanceOf[SourceHandler], module)
      .flatMap {
      case (compileResult, compiledModuleOption) =>
        storeCompiledModule(instance.unrestricted, store, storeName, module.moduleHash, compiledModuleOption).map(compileResult -> _)
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
        trace((module, metadata, ObjectStore.getMetadataString(metadata, "sourceChecksum"), module.moduleHash))
        if (ObjectStore.getMetadataString(metadata, "sourceChecksum") == module.moduleHash)
          Some(module)
        else
          None
    })
}
