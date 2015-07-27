package org.stingray.contester.invokers

import org.stingray.contester.proto.Blobs.{FileBlob, Blob}
import com.twitter.util.Future
import org.stingray.contester.rpc4.RemoteError
import org.stingray.contester.utils.{CommandLineTools, ExecutionArguments}
import org.stingray.contester.proto.Local.LocalExecutionParameters

class Sandbox(val instance: InvokerInstance, val restricted: Boolean, val path: RemoteFileName)  {
  import org.stingray.contester.ContesterImplicits._

  val invoker = instance.invoker
  val i = invoker.api
  val sandboxId = i.file("%" + instance.index + "." + (if (restricted) "R" else "C"))

  val localEnvironment = if (restricted) i.cleanedLocalEnvironment else i.localEnvWithPath

  def put(fileBlob: FileBlob): Future[InvokerRemoteFile] =
    put(fileBlob.getData, fileBlob.getName)

  def put(blob: Blob, name: String): Future[InvokerRemoteFile] =
    i.put(sandboxId / name, blob)

  private def putGridfs(source: String, dest: RemoteFileName): Future[Option[InvokerRemoteFile]] =
    i.putGridfs(Seq(source -> dest), sandboxId.name).map(_.headOption)

  def putGridfs(source: String, dest: String): Future[Option[InvokerRemoteFile]] =
    putGridfs(source, sandboxId / dest)

  def getGridfs(items: Iterable[(RemoteFileName, String, Option[String])]) =
    i.getGridfs(items, sandboxId.name)

  def get(f: RemoteFileName): Future[FileBlob] =
    i.client.get(f.name(i.pathSeparator))

  def glob(name: String, calculateSha1: Boolean) =
    i.glob(sandboxId / name :: Nil, calculateSha1)

  def stat(name: String, calculateSha1: Boolean) =
    i.stat(sandboxId / name :: Nil, calculateSha1)

  def clear = i.client.clear(sandboxId.name).handle {
    case e: RemoteError => throw new TransientError(e)
  }

  def getExecutionParameters(filename: String, arguments: ExecutionArguments): Future[LocalExecutionParameters] =
    Future.value(
      CommandLineTools.fillCommandLine(filename, arguments)
        .setEnvironment(localEnvironment)
        .setSandboxId(sandboxId.name)
        .setCurrentAndTemp(path.name))

  def execute(params: LocalExecutionParameters) =
    invoker.api.execute(params)

  def executeWithParams(params: LocalExecutionParameters) =
    execute(params).map(params -> _)
}
