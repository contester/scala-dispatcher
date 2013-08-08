package org.stingray.contester.invokers

import org.stingray.contester.proto.Local.{LocalExecutionResult, LocalExecutionParameters, FileStat, IdentifyResponse}
import org.stingray.contester.utils.LocalEnvironmentTools
import com.twitter.util.Future
import org.stingray.contester.proto.Blobs.{Blob, FileBlob}
import grizzled.slf4j.Logging
import org.stingray.contester.modules.ModuleFactory

class InvokerId(clientId: IdentifyResponse, val rpc: InvokerRpcClient) {
  import collection.JavaConversions._

  val channel = rpc.channel
  val sandboxes = clientId.getSandboxesList.toIndexedSeq
  val name = clientId.getInvokerId
  val localEnvironment = clientId.getEnvironment
  val cleanedLocalEnvironment = LocalEnvironmentTools.sanitizeLocalEnv(localEnvironment)
  val localEnvWithPath = LocalEnvironmentTools.sanitizeLocalEnv(localEnvironment, Set("path"))
  val platform = clientId.getPlatform
  val pathSeparator = clientId.getPathSeparator

  val disks = clientId.getDisksList.map(file)
  val programFiles = clientId.getProgramFilesList.map(file)

  override def toString =
    name

  def file(name: String) =
    InvokerRemoteFile(this, name)

  def file(st: FileStat) =
    InvokerRemoteFile(this, st)

  def files(names: Iterable[String]) =
    names.map(file)

  def execute(params: LocalExecutionParameters) =
    rpc.execute(params)

  implicit def file2seq(x: RemoteFile) = Seq(x)

  def fileStat(what: Iterable[RemoteFile], expand: Boolean, sandboxId: Option[String], calculateSha1: Boolean) =
    rpc.fileStat(what.map(_.name), expand, sandboxId, calculateSha1).map(_.map(file))

  def glob(what: Iterable[RemoteFile], calculateSha1: Boolean): Future[Iterable[RemoteFile]] =
    fileStat(what, true, None, calculateSha1)

  def stat(what: Iterable[RemoteFile], calculateSha1: Boolean): Future[Iterable[RemoteFile]] =
    fileStat(what, false, None, calculateSha1)

  def get(file: RemoteFile): Future[FileBlob] =
    rpc.get(file.name)

  def put(file: RemoteFile, blob: Blob) =
    rpc.put(FileBlob.newBuilder().setName(file.name).setData(blob).build())

  def putGridfs(items: Iterable[(String, RemoteFile)], sandboxId: String): Future[Iterable[(String, String)]] =
    rpc.gridfsPut(items.map(m => m._1 -> m._2.name), sandboxId)

  def getGridfs(items: Iterable[(RemoteFile, String, Option[String])], sandboxId: String): Future[Iterable[(String, String)]] =
    rpc.gridfsGet(items.map(m => new GridfsGetEntry(m._2, m._1.name, m._3)), sandboxId)

  def executeConnected(first: LocalExecutionParameters, second: LocalExecutionParameters): Future[(LocalExecutionResult, LocalExecutionResult)] =
    rpc.executeConnected(first, second).map(x => (x.getFirst, x.getSecond))
}

class InvokerBig(val i: InvokerId, val moduleFactory: ModuleFactory) extends Logging {
  val caps = moduleFactory.moduleTypes.toSet
  val instances = (0 to (i.sandboxes.length - 1)).map(new InvokerInstance(this, _))
  val channel = i.channel
}

