package org.stingray.contester.utils

import com.twitter.finagle.util.TimerFromNettyTimer
import com.twitter.util.{Duration, Timer, TimeoutException, Future}
import grizzled.slf4j.Logging
import org.jboss.netty.util.HashedWheelTimer

object Utils extends Logging {
  val timer = new TimerFromNettyTimer(new HashedWheelTimer)

  def retry[A](times: Int)(f: => Future[A]): Future[A] =
    if (times > 0) {
      f.rescue {
        case e: TimeoutException =>
          trace("Exception caught, retrying", e)
          retry(times - 1)(f)
      }
    } else f

  def retry[A](f: => Future[A]): Future[A] =
    retry(3)(f)

  def later(atimer: Timer, delay: Duration): Future[Unit] =
    atimer.doLater(delay)()

  def later(delay: Duration): Future[Unit] =
    later(timer, delay)

  def pause[X](delay: Duration)(x: X): Future[X] =
    later(delay).map(_ => x)
}

class FutureOps[A](val repr: Future[A]) {
  def ensureF(f: => Future[Any]): Future[A] =
    repr.transform { v =>
      f.flatMap(k => Future.const(v))
    }
}
