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

  private def storeCompiledModule(sandbox: Sandbox, stored: StoreHandle, sourceHash: String, optModule: Option[CompiledModule]) =
    optModule.map { compiledModule =>
      stored.store.putModule(sandbox, stored.handle.toGridfsPath, sandbox.sandboxId / compiledModule.filename, compiledModule.moduleType)
        .flatMap { result =>
        stored.store.setMetaData(stored.handle.toGridfsPath) { meta =>
          meta.updated("sourceChecksum", sourceHash)
        }.map(_ => Some(result))
      }
    }.getOrElse(Future.None)

  def apply(instance: InvokerInstance, module: Module, stored: StoreHandle): Future[(CompileResult, Option[Module])] =
    justCompile(instance.unrestricted, instance.factory(module.moduleType).asInstanceOf[SourceHandler], module)
      .flatMap {
      case (compileResult, compiledModuleOption) =>
        storeCompiledModule(instance.unrestricted, stored, module.moduleHash, compiledModuleOption).map(compileResult -> _)
    }

  /**
   * Check if this module was already compiled. We use both checksum and storename.
   * TODO: Use memcached too.
   * @param module Source module. We compare module.moduleHash to (storeName).sourceChecksum.
   * @param stored Handle for compiled module
   * @return Future(Some(compiled module) or None).
   */
  def checkIfCompiled(module: Module, stored: StoreHandle): Future[Option[Module]] =
    stored.store.getModuleEx(stored.handle.toGridfsPath).map(_.flatMap {
      case (compiledModule, metadata) =>
        if (ObjectStore.getMetadataString(metadata, "sourceChecksum") == module.moduleHash)
          Some(compiledModule)
        else
          None
    })
}
