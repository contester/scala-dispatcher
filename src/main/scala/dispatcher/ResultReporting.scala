package org.stingray.contester.dispatcher

import com.twitter.util.Future
import org.stingray.contester.common.{TestResult, CompileResult}

trait SingleProgress[T] {
  def compile(r: CompileResult): Future[Boolean]
  def test(id: Int, r: TestResult): Future[Boolean]
  def finish(c: Seq[CompileResult], t: Seq[(Int, TestResult)]): Future[T]
}

trait ProgressReporter[T] {
  def start: Future[SingleProgress[T]]
}
