package org.stingray.contester.engine

import org.stingray.contester.invokers.{InvokerRegistry, SchedulingKey}
import org.stingray.contester.problems.{ProblemManifest, SimpleProblemManifest, Test}
import org.stingray.contester.common._
import com.twitter.util.Future
import org.stingray.contester.modules.ScriptLanguage

class InvokerSimpleApi(val registry: InvokerRegistry, val objectCache: ObjectCache, val baseUrl: String) {
  def compile(key: SchedulingKey, m: Module, stored: String): Future[(CompileResult, Option[Module])] =
    registry(m.moduleType, key, "compile")(Compiler(_, m, stored))

  def test(key: SchedulingKey, m: Module, t: Test, stored: String): Future[TestResult] =
    registry(m.moduleType, key, t)(Tester(_, m, t, stored, objectCache))

  def custom(key: SchedulingKey, m: Module, input: Array[Byte],
             resultName: String): Future[CustomTestResult] =
    registry(m.moduleType, key, "custom")(CustomTester(_, m, input, resultName))

  def sanitize(key: ProblemDescription): Future[SimpleProblemManifest] =
    registry("zip", key, "sanitize")(Sanitizer(_, key, baseUrl))

  def maybeCompile(key: SchedulingKey, m: Module, stored: String): Future[(CompileResult, Option[Module])] = {
    if (ScriptLanguage.list(m.moduleType))
      Future.value((ScriptingLanguageResult, Some(m)))
    else
      compile(key, m, stored)
  }

}