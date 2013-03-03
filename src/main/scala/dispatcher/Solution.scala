package org.stingray.contester.dispatcher

import com.twitter.util.Future
import org.stingray.contester.common.TestResult
import org.stingray.contester.proto.Blobs.Module
import org.stingray.contester.engine.InvokerSimpleApi
import org.stingray.contester.problems.{Test, Problem}
import org.stingray.contester.invokers.SchedulingKey

object Solution {
  def apply(invoker: InvokerSimpleApi, submit: Submit, problem: Problem, reporter: ProgressReporter, greedy: Boolean): Future[Unit] =
    reporter { pr =>

    }

  type NumberedTest = (Int, Test)
  type NumberedTestResult = (Int, TestResult)
  type EvaluatedTestResult = (Boolean, Int, TestResult)

  type ProceedFunc = NumberedTestResult => Boolean
  type Strategy = (Seq[NumberedTest], TestFunc, ProceedFunc) => Future[Seq[EvaluatedTestResult]]
  type TestFunc = NumberedTest => Future[NumberedTestResult]

  type TestAll = Seq[NumberedTest] => Future[Seq[NumberedTestResult]]

  def sequential(tests: Seq[NumberedTest], testFunc: TestFunc, proceedFunc: ProceedFunc): Future[List[EvaluatedTestResult]] =
    if (tests.nonEmpty)
      testFunc(tests.head).map(tr => (proceedFunc(tr), tr._1, tr._2))
      .flatMap { etr =>
        if (etr._1)
          sequential(tests.tail, testFunc, proceedFunc).map(x => etr :: x.toList)
        else Future.value(etr :: Nil)
      }
    else Future.value(Nil)

  def parallel(tests: Seq[NumberedTest], testFunc: TestFunc, proceedFunc: ProceedFunc): Future[List[EvaluatedTestResult]] =
    if (tests.nonEmpty)
      Future.collect(tests.map(testFunc(_).map(tr => (proceedFunc(tr), tr._1, tr._2)))).map(_.toList)
    else Future.value(Nil)

  private[this] def compile(invoker: InvokerSimpleApi, submit: SchedulingKey, m: Module, reporter: SingleProgress) =
    invoker.compile(submit, m).flatMap { cr =>
      reporter.compile(cr._1).map(_ => cr)
    }

  def compileAndTest(invoker: InvokerSimpleApi, submit: Submit, problem: Problem, reporter: SingleProgress, greedy: Boolean) =
    compile(invoker, submit, submit.sourceModule, reporter).flatMap {
      case (compileResult, optionalModule) =>

    }
}



class BinarySolution(invoker: InvokerSimpleApi, binary: Module, submit: SchedulingKey) {
  def test(test: Solution.NumberedTest): Future[Solution.NumberedTestResult] =
    invoker.test(submit, binary, test._2).map(x => test._1 -> x)
}
