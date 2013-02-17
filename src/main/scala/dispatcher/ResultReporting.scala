package org.stingray.contester.dispatcher

import com.twitter.util.Future
import org.stingray.contester.common.{TestResult, CompileResult}

/*
trait SolutionTestingResult {
  def compilation: CompileResult
  def tests: Seq[(Int, TestResult)]
}*/

case class SolutionTestingResult(compilation: CompileResult, tests: Seq[(Int, TestResult)])

trait SingleProgress {
  def compile(r: CompileResult): Future[Unit]
  def test(id: Int, r: TestResult): Future[Unit]
}

trait ProgressReporter {
  def apply(f: SingleProgress => Future[SolutionTestingResult]): Future[Unit]
}
