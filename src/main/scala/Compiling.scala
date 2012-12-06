package org.stingray.contester

import com.twitter.util.Future
import org.stingray.contester.common.CompileResult
import proto.Blobs.Module
import org.stingray.contester.invokers.{Sandbox, CompilerInstance}

object Compiler {
  def justCompile(sandbox: Sandbox, handler: SourceHandler, module: Module) =
    sandbox.put(module, handler.sourceName)
      .flatMap(_ => handler.compile(sandbox))

  def apply(instance: CompilerInstance, module: Module): Future[CompileResult] =
    instance.factory(module.getType).collect {
      case source: SourceHandler =>
        justCompile(instance.comp, source, module)
      case binary: BinaryHandler =>
        Future.value(CompileResult(Seq(), Some(module)))
    }.getOrElse(Future.value(CompileResult(Seq(), None)))
}