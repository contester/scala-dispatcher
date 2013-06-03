package org.stingray.contester.testing

import org.stingray.contester.problems.{Problem, Test}
import org.stingray.contester.common.TestResult
import com.twitter.util.Future
import org.stingray.contester.engine.InvokerSimpleApi
import grizzled.slf4j.Logging
import org.stingray.contester.invokers.SchedulingKey
import org.stingray.contester.proto.Blobs.Module

// test, report, proceed
// - strategy

// (strategy inside class/test)

object Solution {
  type NumberedTest = (Int, Test)
  type NumberedTestResult = (Int, TestResult)
  type EvaluatedTestResult = (Boolean, NumberedTestResult)

  // type ProceedFunc = NumberedTestResult => Boolean
  // type Strategy = (Seq[NumberedTest], TestFunc, ProceedFunc) => Future[Seq[EvaluatedTestResult]]
  // type TestFunc = NumberedTest => Future[NumberedTestResult]

  // type TestAll = Seq[NumberedTest] => Future[Seq[NumberedTestResult]]

}

class SolutionTester(invoker: InvokerSimpleApi) extends Logging {
  private def compile(submit: SchedulingKey, sourceModule: Module, reporter: SingleProgress) =
    invoker.compile(submit, sourceModule).flatMap { cr =>
      reporter.compile(cr._1).map(_ => cr)
    }

  def apply(submit: SchedulingKey, sourceModule: Module, problem: Problem, reporter: ProgressReporter, schoolMode: Boolean): Future[Unit] =
    reporter { pr =>
      compile(submit, sourceModule, pr).flatMap {
        case (compileResult, binaryOption) =>
          binaryOption.map { binary =>
            new BinarySolution(invoker, submit, problem, binary, pr, schoolMode).run
          }.getOrElse(Future.value(Nil)).map(x => SolutionTestingResult(compileResult, x.map(v => v._2)))
      }
    }

  def custom(submit: SchedulingKey, sourceModule: Module, input: Array[Byte]): Future[CustomTestingResult] =
    compile(submit, sourceModule, NullReporter).flatMap {
      case (compileResult, binaryOption) =>
        binaryOption.map { binary =>
          invoker.custom(submit, binary, input).map(Some(_))
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
