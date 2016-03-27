package org.stingray.contester.engine

import org.stingray.contester.invokers.{SchedulingKey, InvokerRegistry}
import org.stingray.contester.problems.{ProblemManifest, Test}
import org.stingray.contester.common._
import com.twitter.util.Future
import org.stingray.contester.modules.ScriptLanguage

class InvokerSimpleApi(val registry: InvokerRegistry, val objectCache: ObjectCache) {
  def compile(key: SchedulingKey, m: Module, stored: HasGridfsPath): Future[(CompileResult, Option[Module])] =
    registry(m.moduleType, key, "compile")(Compiler(_, m, stored))

  def test(key: SchedulingKey, m: Module, t: Test, storePrefix: HasGridfsPath): Future[TestResult] =
    registry(m.moduleType, key, t)(Tester(_, m, t, storePrefix, objectCache))

  def custom(key: SchedulingKey, m: Module, input: Array[Byte],
             resultName: HasGridfsPath): Future[CustomTestResult] =
    registry(m.moduleType, key, "custom")(CustomTester(_, m, input, resultName))

  def sanitize(key: ProblemDescription): Future[ProblemManifest] =
    registry("zip", key, "sanitize")(Sanitizer(_, key))

  def maybeCompile(key: SchedulingKey, m: Module, stored: HasGridfsPath): Future[(CompileResult, Option[Module])] = {
    if (ScriptLanguage.list(m.moduleType))
      Future.value((ScriptingLanguageResult, Some(m)))
    else
      compile(key, m, stored)
  }

}