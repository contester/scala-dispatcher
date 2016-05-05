package org.stingray.contester.utils

import com.twitter.util.Future

import scala.concurrent.{ExecutionContext, Future => ScalaFuture}

object Fu {
  val Done: ScalaFuture[Unit] = ScalaFuture.successful(())

  import com.twitter.bijection.twitter_util.UtilBijections

  implicit def scalaToTwitterFuture[T](f: ScalaFuture[T])(implicit ec: ExecutionContext): Future[T] = {
    UtilBijections.twitter2ScalaFuture[T].invert(f)
  }

  implicit def twitterToScalaFuture[T](f: Future[T])(implicit ec: ExecutionContext): ScalaFuture[T] = {
    UtilBijections.twitter2ScalaFuture[T].apply(f)
  }

  def liftOption[A](f: Option[Future[A]]): Future[Option[A]] =
    f.map(_.map(Some(_))).getOrElse(Future.None)
}