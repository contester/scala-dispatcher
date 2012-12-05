package org.stingray.contester.dispatcher

import com.twitter.util.Future
import org.stingray.contester.common.Result

trait TestingResultReporter {
  def report(r: Result): Future[Unit]
  def finish(isError: Boolean): Future[Unit]
}

