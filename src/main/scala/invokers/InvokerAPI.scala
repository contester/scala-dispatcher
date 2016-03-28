package org.stingray.contester.invokers

import org.stingray.contester.proto.Local.{LocalExecutionResult, LocalExecutionParameters, FileStat, IdentifyResponse}
import org.stingray.contester.utils.LocalEnvironmentTools
import com.twitter.util.Future
import org.stingray.contester.proto.Blobs.{Blob, FileBlob}
import org.stingray.contester.modules.ModuleHandler

class InvokerAPI(clientId: IdentifyResponse, val client: InvokerRpcClient) {
  import collection.JavaConversions._

  val sandboxes = clientId.getSandboxesList.toIndexedSeq
  val name = clientId.getInvokerId

  val cleanedLocalEnvironment = LocalEnvironmentTools.sanitizeLocalEnv(clientId.getEnvironment)
  val localEnvWithPath = LocalEnvironmentTools.sanitizeLocalEnv(clientId.getEnvironment, Set("path"))

  val platform = clientId.getPlatform
  val pathSeparator = clientId.getPathSeparator

  val disks = clientId.getDisksList.map(file)
  val programFiles = clientId.getProgramFilesList.map(file)

  override def toString =
    name

  def file(name: String): RemoteFileName =
    new RemoteFileName(name, Some(pathSeparator))

  def file(st: FileStat): InvokerRemoteFile =
    new InvokerRemoteFile(this, st)

  def execute(params: LocalExecutionParameters) =
    client.execute(params)

  def fileStat(what: Iterable[RemoteFileName], expand: Boolean, sandboxId: Option[String], calculateSha1: Boolean) =
    client.fileStat(what.map(_.name(pathSeparator)), expand, sandboxId, calculateSha1).map(_.map(file))

  def glob(what: Iterable[RemoteFileName], calculateSha1: Boolean): Future[Iterable[InvokerRemoteFile]] =
    fileStat(what, true, None, calculateSha1)

  def stat(what: Iterable[RemoteFileName], calculateSha1: Boolean): Future[Iterable[InvokerRemoteFile]] =
    fileStat(what, false, None, calculateSha1)

  def put(remote: RemoteFileName, blob: Blob) =
    client.put(FileBlob.newBuilder().setName(remote.name).setData(blob).build()).map(file)

  def putGridfs(items: Iterable[(String, RemoteFileName)], sandboxId: String): Future[Iterable[InvokerRemoteFile]] =
    client.gridfsPut(items.map(m => m._1 -> m._2.name(pathSeparator)), sandboxId).map(_.map(file))

  def getGridfs(items: Iterable[(RemoteFileName, String, Option[String])], sandboxId: String): Future[Iterable[InvokerRemoteFile]] =
    client.gridfsGet(items.map(m => new GridfsGetEntry(m._1.name(pathSeparator), m._2, m._3)), sandboxId).map(_.map(file))

  def copyToStorage(items: Iterable[CopyToStorage], sandboxId: String): Future[Iterable[InvokerRemoteFile]] =
    client.gridfsGet(items.map(m => new GridfsGetEntry(m.local.name(pathSeparator), m.storage.s, m.moduleType)), sandboxId).map(_.map(file))

  def executeConnected(first: LocalExecutionParameters, second: LocalExecutionParameters): Future[(LocalExecutionResult, LocalExecutionResult)] =
    client.executeConnected(first, second).map(x => (x.getFirst, x.getSecond))
}

class Invoker(val api: InvokerAPI, val moduleFactory: Map[String, ModuleHandler]) {
  val caps = moduleFactory.keySet
  val instances = api.sandboxes.zipWithIndex.map(x => new InvokerInstance(this, x._2, x._1))
}

