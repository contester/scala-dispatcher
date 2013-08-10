package org.stingray.contester.testing

import org.stingray.contester.problems.{Problem, Test}
import org.stingray.contester.common.{GridfsObjectStore, Module, TestResult}
import com.twitter.util.Future
import org.stingray.contester.engine.InvokerSimpleApi
import grizzled.slf4j.Logging
import org.stingray.contester.invokers.SchedulingKey

object Solution {
  type NumberedTest = (Int, Test)
  type NumberedTestResult = (Int, TestResult)
  type EvaluatedTestResult = (Boolean, NumberedTestResult)
}

class SolutionTester(invoker: InvokerSimpleApi) extends Logging {
  def apply(submit: SchedulingKey, sourceModule: Module, problem: Problem, reporter: ProgressReporter, schoolMode: Boolean, store: GridfsObjectStore, storeBase: String, submitId: Int): Future[Unit] =
    invoker.compile(submit, sourceModule, store, storeBase + "submit/%d/compiledModule".format(submitId))
      .flatMap { compiled =>
        reporter { pr =>
          compiled._2.map { binary =>
            new BinarySolution(invoker, submit, problem, binary, pr, schoolMode).run
          }.getOrElse(Future.value(Nil)).map(x => SolutionTestingResult(compiled._1, x.map(v => v._2)))
        }
    }

  def custom(submit: SchedulingKey, sourceModule: Module, input: Array[Byte], store: GridfsObjectStore, storeBase: String, testingId: Int): Future[CustomTestingResult] =
    invoker.compile(submit, sourceModule, store, storeBase + "eval/%d/compiledModule".format(testingId)).flatMap {
      case (compileResult, binaryOption) =>
        binaryOption.map { binary =>
          invoker.custom(submit, binary, input, store, storeBase + "eval/%d/output".format(testingId)).map(Some(_))
        }.getOrElse(Future.None).map(x => CustomTestingResult(compileResult, x))
    }
}

class BinarySolution(invoker: InvokerSimpleApi, submit: SchedulingKey, problem: Problem, binary: Module, reporter: SingleProgress, schoolMode: Boolean) extends Logging with TestingStrategy {
  private def proceed(r: Solution.NumberedTestResult): Boolean =
    r._2.success || (schoolMode && r._1 != 1)

  def test(test: Solution.NumberedTest): Future[Solution.EvaluatedTestResult] =
    invoker.test(submit, binary, test._2)
      .map(x => test._1 -> x)
      .flatMap { result =>
      reporter.test(result._1, result._2)
        .map(_ => (proceed(result), result))
    }

  def run =
    if (schoolMode) school(problem.toSeq)
    else sequential(problem.toSeq)
}
