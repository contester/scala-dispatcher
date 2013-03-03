package org.stingray.contester.dispatcher

import com.twitter.util.Future
import org.stingray.contester.common.TestResult
import org.stingray.contester.proto.Blobs.Module
import org.stingray.contester.engine.InvokerSimpleApi
import org.stingray.contester.problems.{Test, Problem}
import org.stingray.contester.invokers.SchedulingKey
import grizzled.slf4j.Logging

// test, report, proceed
// - strategy

// (strategy inside class/test)

object Solution {
  def apply(invoker: InvokerSimpleApi, submit: SchedulingKey, source: Module, problem: Problem, reporter: ProgressReporter, schoolMode: Boolean): Future[Unit] =
    reporter { pr =>
      compile(invoker, submit, source, pr).flatMap {
        case (compileResult, Some(binary)) =>
          new BinarySolution(invoker, binary, submit, pr, schoolMode, problem).run.map(x => SolutionTestingResult(compileResult, x.map(v => v._2 -> v._3)))
      }
    }

  type NumberedTest = (Int, Test)
  type NumberedTestResult = (Int, TestResult)
  type EvaluatedTestResult = (Boolean, Int, TestResult)

  type ProceedFunc = NumberedTestResult => Boolean
  type Strategy = (Seq[NumberedTest], TestFunc, ProceedFunc) => Future[Seq[EvaluatedTestResult]]
  type TestFunc = NumberedTest => Future[NumberedTestResult]

  type TestAll = Seq[NumberedTest] => Future[Seq[NumberedTestResult]]

  private[this] def compile(invoker: InvokerSimpleApi, submit: SchedulingKey, m: Module, reporter: SingleProgress) =
    invoker.compile(submit, m).flatMap { cr =>
      reporter.compile(cr._1).map(_ => cr)
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

class BinarySolution(invoker: InvokerSimpleApi, binary: Module, submit: SchedulingKey, reporter: SingleProgress, schoolMode: Boolean, problem: Problem) extends Logging with TestingStrategy {
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
