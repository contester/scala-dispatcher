package org.stingray.contester.engine

import org.stingray.contester.invokers.{SchedulingKey, InvokerRegistry}
import org.stingray.contester.problems.Test
import org.stingray.contester.common.{AlreadyCompiledResult, GridfsObjectStore, Module}
import com.twitter.util.Future

class InvokerSimpleApi(val invoker: InvokerRegistry) {
  def compile(key: SchedulingKey, m: Module, store: GridfsObjectStore, resultName: String) =
    invoker(m.moduleType, key, "compile")(Compiler(_, m, store, resultName))

  def test(key: SchedulingKey, m: Module, t: Test, store: GridfsObjectStore, storePrefix: String) =
    invoker(m.moduleType, key, t)(Tester(_, m, t))

  def custom(key: SchedulingKey, m: Module, input: Array[Byte], store: GridfsObjectStore, resultName: String) =
    invoker(m.moduleType, key, "custom")(CustomTester(_, m, input, store, resultName))

  def maybeCompile(key: SchedulingKey, m: Module, store: GridfsObjectStore, resultName: String) = {
    Compiler.checkIfCompiled(m, store, resultName).flatMap { maybeCompiled =>
      if (maybeCompiled.isDefined)
        Future.value((AlreadyCompiledResult, maybeCompiled))
      else
        compile(key, m, store, resultName)
    }
  }

}