package org.stingray.contester

import com.twitter.util.Future
import org.stingray.contester.utils._
import proto.LocalExecutionParameters
import org.stingray.contester.invokers.{RemoteFileName, FileListOps}

object ContesterImplicits {
  implicit class FutureOption[A](x: Future[Option[A]]) {
    def flatMapOption[B](f: A => Future[B]): Future[Option[B]] =
      x.flatMap {
        case Some(v) => f(v).map(Some(_))
        case None => Future.None
      }

    def mapOption[B](f: A => B): Future[Option[B]] =
      x.map {
        case Some(v) => Some(f(v))
        case None => None
      }
  }


  implicit def CreateExecutionArgumentsList(x: List[String]): ExecutionArguments = new ExecutionArgumentsList(x)
  implicit def CreateExecutionArgumentsString(x: String): ExecutionArguments = new ExecutionArgumentsString(x)

  implicit def ToRichLocalExecutionParameters(x: LocalExecutionParameters): RichLocalExecutionParameters =
    new RichLocalExecutionParameters(x)

  implicit def FromRichLocalExecutionParameters(x: RichLocalExecutionParameters): LocalExecutionParameters =
    x.repr

  implicit def flist2ops(x: Iterable[RemoteFileName]): FileListOps = new FileListOps(x)
  implicit def ops2flist(x: FileListOps): Iterable[RemoteFileName] = x.repr
  implicit def flist2str(x: Iterable[RemoteFileName]): Iterable[String] = x.map(_.name)

  implicit def optionFuture2futureOption[A](x: Option[Future[A]]): Future[Option[A]] = x.map(_.map(Some(_))).getOrElse(Future.None)
}
