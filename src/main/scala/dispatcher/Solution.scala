package org.stingray.contester.dispatcher

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common.{RunResult, TesterRunResult, TestResult}
import org.stingray.contester.proto.Blobs.Module
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.engine.{Tester, Compiler}
import org.stingray.contester.problems.{Test, Problem}
import collection.SortedMap

object Solution {
  def test(invoker: InvokerRegistry, submit: SubmitObject, problem: Problem, reporter: CombinedResultReporter) =
    invoker.wrappedGetClear(submit.sourceModule.getType, submit, "compile")(Compiler(_, submit.sourceModule))
      .flatMap { r =>
        reporter.compileResult(r).flatMap { _ =>
          if (r.success) {
            if (submit.schoolMode)
              new SchoolSolution(invoker, r.module.get, problem, reporter, submit).start
            else
              new ACMSolution(invoker, r.module.get, problem, reporter, submit).start
          } else Future.Done
        }
      }.transform { x =>
        reporter.finish(x.isThrow).flatMap(_ => Future.const(x))
      }
}

trait SimpleSolution {
  def reporter: CombinedResultReporter
  def invoker: InvokerRegistry
  def problem: Problem
  def module: Module
  def submit: SubmitObject

  def start: Future[Unit]

  private def saveResult(solution: RunResult, tester: Option[TesterRunResult], testId: Int): Future[TestResult] = {
    val result = TestResult(solution, tester, testId)
    reporter.testResult(result).map(_ => result)
  }

  def test(test: Test): Future[TestResult] =
    reporter.getId.flatMap { testingId =>
      invoker.wrappedGetClear(module.getType, submit, test)(Tester(_, module, test))
        .flatMap {
        case (solution, tester) => saveResult(solution, tester, test.testId)
      }
    }
}

class ACMSolution(val invoker: InvokerRegistry, val module: Module, val problem: Problem, val reporter: CombinedResultReporter, val submit: SubmitObject) extends SimpleSolution with Logging {
  def start: Future[Unit] =
    test(problem.head._2).flatMap(testDone(_, problem.tail))

  private def testDone(result: TestResult, tail: SortedMap[Int, Test]): Future[Unit] = {
    if ((result.success) && tail.nonEmpty) {
      test(tail.head._2).flatMap(testDone(_, tail.tail))
    } else {
      Future.value()
    }
  }

}

class SchoolSolution(val invoker: InvokerRegistry, val module: Module, val problem: Problem, val reporter: CombinedResultReporter, val submit: SubmitObject) extends SimpleSolution with Logging {
  private def first(result: TestResult): Future[Unit] =
    (if (result.success)
      Future.collect(
        Seq(Future.value(result)) ++ (problem.tail.values map(test(_)))
      )
    else Future.value(Seq(result))).unit

  def start: Future[Unit] =
    test(problem.head._2).flatMap(first(_))
}


