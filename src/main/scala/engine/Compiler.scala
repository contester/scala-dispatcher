package org.stingray.contester.engine

import org.stingray.contester.invokers.{CompilerInstance, Sandbox}
import org.stingray.contester.modules.{BinaryHandler, SourceHandler}
import org.stingray.contester.proto.Blobs.Module
import com.twitter.util.Future
import org.stingray.contester.common.{NoModuleCompileResult, BinaryModuleCompileResult, CompileResult}

object Compiler {
  def justCompile(sandbox: Sandbox, handler: SourceHandler, module: Module) =
    sandbox.put(module, handler.sourceName)
      .flatMap(_ => handler.compile(sandbox))

  def apply(instance: CompilerInstance, module: Module): Future[(CompileResult, Option[Module])] =
    instance.factory(module.getType).collect {
      case source: SourceHandler =>
        justCompile(instance.comp, source, module)
      case binary: BinaryHandler =>
        Future.value(BinaryModuleCompileResult -> Some(module))
      case _ =>
        Future.value(NoModuleCompileResult -> None)
    }.get
}
