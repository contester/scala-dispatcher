package org.stingray.contester.invokers

import org.stingray.contester.utils.LocalEnvironmentTools
import com.twitter.util.Future
import org.stingray.contester.modules.{ModuleHandler, SpecializedModuleFactory}
import org.stingray.contester.proto._

class InvokerAPI(clientId: IdentifyResponse, val client: InvokerRpcClient) {
  val sandboxes = clientId.sandboxes.toIndexedSeq
  val name = clientId.invokerId

  val cleanedLocalEnvironment = LocalEnvironmentTools.sanitizeLocalEnv(clientId.getEnvironment)
  val localEnvWithPath = LocalEnvironmentTools.sanitizeLocalEnv(clientId.getEnvironment, Set("path"))

  val platform = clientId.platform
  val pathSeparator = clientId.pathSeparator

  val disks = clientId.disks.map(file)
  val programFiles = clientId.programFiles.map(file)

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
    client.put(FileBlob(name = remote.name, data = Some(blob))).map(file)

  def putGridfs(items: Seq[(String, RemoteFileName)], sandboxId: String): Future[Seq[InvokerRemoteFile]] =
    client.gridfsCopy(items.map(m => CopyOperation(m._1, m._2.name(pathSeparator))), sandboxId).map(_.map(file))

  def getGridfs(items: Seq[(RemoteFileName, String, Option[String])], sandboxId: String): Future[Seq[InvokerRemoteFile]] =
    client.gridfsGet(items.map(m => new GridfsGetEntry(m._1.name(pathSeparator), m._2, m._3)), sandboxId).map(_.map(file))

  def copyToStorage(items: Seq[CopyToStorage], sandboxId: String): Future[Seq[InvokerRemoteFile]] =
    client.gridfsGet(items.map(m => new GridfsGetEntry(m.local.name(pathSeparator), m.storage.s, m.moduleType)), sandboxId).map(_.map(file))

  def executeConnected(first: LocalExecutionParameters, second: LocalExecutionParameters): Future[(LocalExecutionResult, LocalExecutionResult)] =
    client.executeConnected(first, second).map(x => (x.getFirst, x.getSecond))
}

class Invoker(val api: InvokerAPI, val moduleFactory: SpecializedModuleFactory) {
  val caps = moduleFactory.keySet
  val instances = api.sandboxes.zipWithIndex.map(x => new InvokerInstance(this, x._2, x._1))
}

