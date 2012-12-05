package org.stingray.contester

import com.twitter.util.Future
import org.stingray.contester.common.ModuleOps
import org.stingray.contester.utils._
import proto.Blobs.Module
import proto.Local.LocalExecutionParameters
import scala.Some

object ContesterImplicits {
  implicit def CreateExecutionArgumentsList(x: List[String]): ExecutionArguments = new ExecutionArgumentsList(x)
  implicit def CreateExecutionArgumentsString(x: String): ExecutionArguments = new ExecutionArgumentsString(x)

  implicit def ToRichLocalExecutionParameters(x: LocalExecutionParameters): RichLocalExecutionParameters =
    new RichLocalExecutionParameters(x)

  implicit def FromRichLocalExecutionParameters(x: RichLocalExecutionParameters): LocalExecutionParameters =
    x.repr


  implicit def addModuleOps(x: Module): ModuleOps = new ModuleOps(x)
  implicit def removeModuleOps(x: ModuleOps): Module = x.repr

  implicit def future2ops[A](x: Future[A]): FutureOps[A] = new FutureOps(x)
  implicit def ops2future[A](x: FutureOps[A]): Future[A] = x.repr

  implicit def flist2ops(x: Iterable[RemoteFile]): FileListOps = new FileListOps(x)
  implicit def ops2flist(x: FileListOps): Iterable[RemoteFile] = x.repr
  implicit def flist2str(x: Iterable[RemoteFile]): Iterable[String] = x.map(_.name)

  implicit def optionFuture2futureOption[A](x: Option[Future[A]]): Future[Option[A]] = x.map(_.map(Some(_))).getOrElse(Future.None)
}
