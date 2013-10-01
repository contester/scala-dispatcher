package org.stingray.contester.engine

import org.stingray.contester.invokers.{SchedulingKey, InvokerRegistry}
import org.stingray.contester.problems.{ProblemManifest, Test}
import org.stingray.contester.common._
import com.twitter.util.Future
import org.stingray.contester.modules.ScriptLanguage

class InvokerSimpleApi(val invoker: InvokerRegistry) {
  def compile(key: SchedulingKey, m: Module, store: GridfsObjectStore,
              resultName: String): Future[(CompileResult, Option[Module])] =
    invoker(m.moduleType, key, "compile")(Compiler(_, m, store, resultName))

  def test(key: SchedulingKey, m: Module, t: Test, store: GridfsObjectStore, storePrefix: String): Future[TestResult] =
    invoker(m.moduleType, key, t)(Tester(_, m, t, store, storePrefix))

  def custom(key: SchedulingKey, m: Module, input: Array[Byte], store: GridfsObjectStore,
             resultName: String): Future[CustomTestResult] =
    invoker(m.moduleType, key, "custom")(CustomTester(_, m, input, store, resultName))

  def sanitize(key: ProblemDescription): Future[ProblemManifest] =
    invoker("zip", key, "sanitize")(Sanitizer(_, key))

  def maybeCompile(key: SchedulingKey, m: Module, store: GridfsObjectStore,
                   resultName: String): Future[(CompileResult, Option[Module])] = {
    if (ScriptLanguage.list(m.moduleType))
      Future.value((ScriptingLanguageResult, Some(m)))
    else
      Compiler.checkIfCompiled(m, store, resultName).flatMap { maybeCompiled =>
        if (maybeCompiled.isDefined)
          Future.value((AlreadyCompiledResult, maybeCompiled))
        else
          compile(key, m, store, resultName)
      }
  }

}