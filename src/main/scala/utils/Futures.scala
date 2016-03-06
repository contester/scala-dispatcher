package org.stingray.contester.utils

import com.twitter.finagle.util.HashedWheelTimer
import com.twitter.util.{Duration, Timer, TimeoutException, Future}
import grizzled.slf4j.Logging

import scala.concurrent.{Future => ScalaFuture, Promise, ExecutionContext}
import scala.util.{Try, Failure, Success}

object Utils extends Logging {
  val timer = HashedWheelTimer.Default

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

object Fu {
  val Done: ScalaFuture[Unit] = ScalaFuture.successful(())

  import com.twitter.bijection.twitter_util.UtilBijections

  implicit def scalaToTwitterFuture[T](f: ScalaFuture[T])(implicit ec: ExecutionContext): Future[T] = {
    UtilBijections.twitter2ScalaFuture[T].invert(f)
  }

  implicit def twitterToScalaFuture[T](f: Future[T])(implicit ec: ExecutionContext): ScalaFuture[T] = {
    UtilBijections.twitter2ScalaFuture[T].apply(f)
  }
}