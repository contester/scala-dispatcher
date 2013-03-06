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
  type EvaluatedTestResult = (Boolean, Int, TestResult)

  type ProceedFunc = NumberedTestResult => Boolean
  type Strategy = (Seq[NumberedTest], TestFunc, ProceedFunc) => Future[Seq[EvaluatedTestResult]]
  type TestFunc = NumberedTest => Future[NumberedTestResult]

  type TestAll = Seq[NumberedTest] => Future[Seq[NumberedTestResult]]

}

class SolutionTester(invoker: InvokerSimpleApi) extends Logging {
  def compile(submit: SchedulingKey, sourceModule: Module, reporter: SingleProgress) =
    invoker.compile(submit, sourceModule).flatMap { cr =>
      reporter.compile(cr._1).map(_ => cr)
    }

  def apply(submit: SchedulingKey, sourceModule: Module, problem: Problem, reporter: ProgressReporter, schoolMode: Boolean): Future[Unit] =
    reporter { pr =>
      compile(submit, sourceModule, pr).flatMap {
        case (compileResult, binaryOption) =>
          binaryOption.map { binary =>
            new BinarySolution(invoker, submit, problem, binary, pr, schoolMode).run
          }.getOrElse(Future.value(Nil)).map(x => SolutionTestingResult(compileResult, x.map(v => v._2 -> v._3)))
      }
    }

  def custom(submit: SchedulingKey, sourceModule: Module, input: Array[Byte], reporter: ProgressReporter) =
    reporter { pr =>
      compile(submit, sourceModule, pr).flatMap {
        case (compileResult, binaryOption) =>
          binaryOption.map { binary =>
            invoker.custom(submit, binary, input)
          }.getOrElse()
      }
    }
}

trait TestingStrategy {
  def test(test: Solution.NumberedTest): Future[Solution.EvaluatedTestResult]

  def sequential(tests: Seq[Solution.NumberedTest]): Future[List[Solution.EvaluatedTestResult]] =
    if (tests.nonEmpty)
      test(tests.head).flatMap { etr =>
        if (etr._1)
          sequential(tests.tail).map(x => etr :: x.toList)
        else Future.value(etr :: Nil)
      }
    else Future.value(Nil)

  def parallel(tests: Seq[Solution.NumberedTest]): Future[List[Solution.EvaluatedTestResult]] =
    if (tests.nonEmpty)
      Future.collect(tests.map(test(_))).map(_.toList)
    else Future.value(Nil)

  def school(tests: Seq[Solution.NumberedTest]): Future[List[Solution.EvaluatedTestResult]] =
    sequential(Seq(tests.head)).flatMap { first =>
      {
        if (first.head._1)
          parallel(tests.tail)
        else
          Future.value(Nil)
      }.map(x => first ++ x)
    }
}

// ACM: seq
// School: 1, parallel

class BinarySolution(invoker: InvokerSimpleApi, submit: SchedulingKey, problem: Problem, binary: Module, reporter: SingleProgress, schoolMode: Boolean) extends Logging with TestingStrategy {
  def proceed(r: Solution.NumberedTestResult): Boolean =
    r._2.success || (schoolMode && r._1 != 1)

  def test(test: Solution.NumberedTest): Future[Solution.EvaluatedTestResult] =
    invoker.test(submit, binary, test._2)
      .map(x => test._1 -> x)
      .flatMap { result =>
      reporter.test(result._1, result._2)
        .map(_ => (proceed(result), result._1, result._2))
    }

  def run =
    if (schoolMode) school(problem.toSeq)
    else sequential(problem.toSeq)
}
