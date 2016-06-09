package org.stingray.contester.testing

import org.stingray.contester.problems.{Problem, Test}
import org.stingray.contester.common._
import com.twitter.util.Future
import org.stingray.contester.engine.{InvokerSimpleApi, TestOptions}
import grizzled.slf4j.Logging
import org.stingray.contester.invokers.SchedulingKey

import scala.Some

object Solution {
  type NumberedTest = (Int, Test)
  type NumberedTestResult = (Int, Result)
  type EvaluatedTestResult = (Boolean, NumberedTestResult)
}

import scala.concurrent.ExecutionContext.Implicits.global
import org.stingray.contester.utils.Fu._

class SolutionTester(invoker: InvokerSimpleApi) extends Logging {
  def apply(submit: SchedulingKey, sourceModule: Module, problem: Problem, progress: SingleProgress,
      schoolMode: Boolean, store: TestingResultStore, state: Map[Int, Result], stdio: Boolean): Future[SolutionTestingResult] = {
    invoker.maybeCompile(submit, sourceModule,
      store.compiledModule)
      .flatMap { compiled =>
          progress.compile(compiled._1).flatMap { _ =>
            compiled._2.map { binary =>
              new BinarySolution(invoker, store, submit, problem,
                binary, progress, schoolMode, state, stdio).run
            }.getOrElse(Future.value(Nil)).map(x => SolutionTestingResult(compiled._1, x.map(v => v._2)))
          }
    }
  }

  // TODO: Move this to a different class
  def custom(submit: SchedulingKey, sourceModule: Module, input: Array[Byte],
      store: SingleTestStore): Future[CustomTestingResult] =
    invoker.maybeCompile(submit, sourceModule, store.compiledModule).flatMap {
      case (compileResult, binaryOption) =>
        binaryOption.map { binary =>
          invoker.custom(submit, binary, input, store.output).map(Some(_))
        }.getOrElse(Future.None).map(x => CustomTestingResult(compileResult, x))
    }
}

class BinarySolution(invoker: InvokerSimpleApi, store: TestOutputStore,
    submit: SchedulingKey, problem: Problem,
    binary: Module, reporter: SingleProgress, schoolMode: Boolean, state: Map[Int, Result], stdio: Boolean) extends Logging with TestingStrategy {
  private def proceed(r: Solution.NumberedTestResult): Boolean =
    r._2.success || (schoolMode && r._1 != 1)

  def test(test: Solution.NumberedTest): Future[Solution.EvaluatedTestResult] = {
    state.get(test._1).map(r => Future.value((r.success, (test._1, r)))).getOrElse {
      invoker.test(submit, binary, test._2, store.testOutput(test._1), TestOptions(stdio))
        .map(x => test._1 -> x)
        .flatMap { result =>
        reporter.test(result._1, result._2)
          .map(_ => proceed(result) -> result)
      }
    }
  }

  def run =
    if (schoolMode) school(problem.toSeq)
    else sequential(problem.toSeq)
}
