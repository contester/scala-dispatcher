package org.stingray.contester.dispatcher

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common.TestResult
import org.stingray.contester.proto.Blobs.Module
import org.stingray.contester.engine.InvokerSimpleApi
import org.stingray.contester.problems.{Test, Problem}

object Solution {
  def apply(invoker: InvokerSimpleApi, submit: Submit, problem: Problem, reporter: ProgressReporter, greedy: Boolean): Future[Unit] =
    reporter(pr => new Solution(invoker, submit, problem, pr, greedy).apply)

  type NumberedTest = (Int, Test)
  type NumberedTestResult = (Int, TestResult)
  type EvaluatedTestResult = (Boolean, Int, TestResult)

  type ProceedFunc = NumberedTestResult => Boolean
  type Strategy = (Seq[NumberedTest], TestFunc, ProceedFunc) => Future[Seq[EvaluatedTestResult]]
  type TestFunc = NumberedTest => Future[NumberedTestResult]

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

}

class Solution(invoker: InvokerSimpleApi, submit: Submit, problem: Problem, reporter: SingleProgress, greedy: Boolean) extends Logging {
  private[this] def compile(m: Module) =
    invoker.compile(submit, m).flatMap {
      case (compileResult, next) => reporter.compile(compileResult).map { proceed =>
        (proceed, compileResult, next)
      }
    }

  private[this] def test(test: (Int, Test), m: Module) =
    invoker.test(submit, m, test._2).flatMap { tr =>
      reporter.test(test._1, tr).map { proceed =>
        (proceed, test._1, tr)
      }
    }

  private[this] def testSequence(tests: Seq[(Int, Test)], m: Module): Future[List[(Boolean, Int, TestResult)]] =
    if (tests.nonEmpty)
      test(tests.head, m).flatMap { tr =>
          if (tr._1)
            testSequence(tests.tail, m).map(x => tr :: x)
          else Future.value(tr :: Nil)
      } else Future.value(Nil)

  private[this] def testParallel(tests: Seq[(Int, Test)], m: Module): Future[List[(Boolean, Int, TestResult)]] =
    if (tests.nonEmpty)
      Future.collect(tests.map(test(_, m))).map(_.toList)
    else Future.value(Nil)

  private[this] def startTesting(m: Module) =
    testSequence(problem.headOption.toSeq, m).flatMap { head =>
      if (head.last._1) {
        {
          if (greedy)
            testParallel(_, _)
          else testSequence(_, _)
        }.apply(problem.tail.toSeq, m).map { tail =>
          head ++ tail
        }
      } else Future.value(head)
    }

  def apply: Future[SolutionTestingResult] =
    compile(submit.sourceModule).flatMap {
      case (proceed, compileResult, moduleOption) =>
        (if (proceed)
          startTesting(moduleOption.get)
        else Future.value(Nil)).map { testResults =>
          (compileResult, testResults.map(x => (x._2, x._3)))
        }
    }

}
