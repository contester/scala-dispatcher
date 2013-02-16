package org.stingray.contester.dispatcher

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common.TestResult
import org.stingray.contester.proto.Blobs.Module
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.engine.{Tester, Compiler}
import org.stingray.contester.problems.{Test, Problem}

object Solution {
  def apply[T](invoker: InvokerRegistry, submit: Submit, problem: Problem, reporter: ProgressReporter[T], greedy: Boolean): Future[T] =
    reporter.start.flatMap(pr => new Solution[T](invoker, submit, problem, pr, greedy).apply)
}

class Solution[T](invoker: InvokerRegistry, submit: Submit, problem: Problem, reporter: SingleProgress[T], greedy: Boolean) extends Logging {
  private[this] def compile(m: Module) =
    invoker(submit.sourceModule.getType, submit, "compile")(Compiler(_, m)).flatMap {
      case (compileResult, next) => reporter.compile(compileResult).map { proceed =>
        (proceed, compileResult, next)
      }
    }

  private[this] def test(test: (Int, Test), m: Module) =
    invoker(m.getType, submit, test._2)(Tester(_, m, test._2)).flatMap { tr =>
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

  def apply: Future[T] =
    compile(submit.sourceModule).flatMap {
      case (proceed, compileResult, moduleOption) =>
        (if (proceed)
          startTesting(moduleOption.get)
        else Future.value(Nil)).flatMap { testResults =>
          reporter.finish(compileResult :: Nil, testResults.map(x => (x._2, x._3)))
        }
    }

}
