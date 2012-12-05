package org.stingray.contester.dispatcher

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common.{RunResult, TesterRunResult, TestResult}
import org.stingray.contester.polygon.SanitizedProblem
import org.stingray.contester.proto.Blobs.Module
import org.stingray.contester.{Tester, Compiler, SubmitObject}
import org.stingray.contester.invokers.InvokerRegistry

object Solution {
  def test(invoker: InvokerRegistry, submit: SubmitObject, problem: SanitizedProblem, reporter: CombinedResultReporter) =
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
  def problem: SanitizedProblem
  def module: Module
  def submit: SubmitObject

  def start: Future[Unit]

  private def saveResult(solution: RunResult, tester: Option[TesterRunResult], testId: Int): Future[TestResult] = {
    val result = TestResult(solution, tester, testId)
    reporter.testResult(result).map(_ => result)
  }

  def test(testId: Int): Future[TestResult] =
    reporter.getId.flatMap { testingId =>
      val t = problem.getTest(testId)
      invoker.wrappedGetClear(module.getType, submit, t)(Tester(_, module, t))
        .flatMap {
        case (solution, tester) => saveResult(solution, tester, testId)
      }
    }
}

class ACMSolution(val invoker: InvokerRegistry, val module: Module, val problem: SanitizedProblem, val reporter: CombinedResultReporter, val submit: SubmitObject) extends SimpleSolution with Logging {
  def start: Future[Unit] =
    test(1).flatMap(testDone(_, 2))

  private def testDone(result: TestResult, nextTest: Int): Future[Unit] = {
    if ((result.success) && (nextTest <= problem.testCount)) {
      test(nextTest).flatMap(testDone(_, nextTest + 1))
    } else {
      Future.value()
    }
  }

}

class SchoolSolution(val invoker: InvokerRegistry, val module: Module, val problem: SanitizedProblem, val reporter: CombinedResultReporter, val submit: SubmitObject) extends SimpleSolution with Logging {
  private def first(result: TestResult): Future[Unit] =
    (if (result.success)
      Future.collect(
        Seq(Future.value(result)) ++ (2 to problem.testCount map(test(_)))
      )
    else Future.value(Seq(result))).unit

  def start: Future[Unit] =
    test(1).flatMap(first(_))
}


