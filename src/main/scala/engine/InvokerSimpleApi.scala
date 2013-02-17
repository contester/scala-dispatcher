package org.stingray.contester.engine

import org.stingray.contester.invokers.{SchedulingKey, InvokerRegistry}
import org.stingray.contester.proto.Blobs.Module
import org.stingray.contester.problems.Test

class InvokerSimpleApi(val invoker: InvokerRegistry) {
  def compile(key: SchedulingKey, m: Module) =
    invoker(m.getType, key, "compile")(Compiler(_, m))

  def test(key: SchedulingKey, m: Module, t: Test) =
    invoker(m.getType, key, t)(Tester(_, m, t))
}