package org.stingray.contester.invokers

import com.twitter.util.Future
import org.stingray.contester.proto._
import org.stingray.contester.rpc4.RpcClient

case class GridfsGetEntry(local: String, remote: String, moduleType: Option[String]) {
  def toCopyOperation =
    CopyOperation(localFileName = local, remoteLocation = remote, upload = true, moduleType=moduleType.getOrElse(""))
}

class InvokerRpcClient(val client: RpcClient) {
  def getBinaryType(pathname: String): Future[BinaryTypeResponse] =
    client.call("Contester.GetBinaryType",
      BinaryTypeRequest(pathname = pathname),
      BinaryTypeResponse)

  def execute(params: LocalExecutionParameters): Future[LocalExecutionResult] =
    client.call("Contester.LocalExecute", params, LocalExecutionResult)

  def clear(sandbox: String) =
    client.callNoResult("Contester.Clear", ClearSandboxRequest(sandbox = sandbox))

  def put(file: FileBlob): Future[FileStat] =
    client.call("Contester.Put", file, FileStat)

  def get(name: String) =
    client.call("Contester.Get", GetRequest(name), FileBlob)

  def fileStat(names: Iterable[String], expand: Boolean, sandboxId: Option[String], calculateChecksum: Boolean) = {
    client.call("Contester.Stat",
      StatRequest(names.toSeq, sandboxId.getOrElse(""), expand=expand, calculateChecksum=calculateChecksum),
      FileStats)
  }.map(_.entries)

  def identify(contesterId: String) =
    client.call("Contester.Identify",
      IdentifyRequest(contesterId = contesterId),
      IdentifyResponse)

  def gridfsCopy(operations: Seq[CopyOperation], sandboxId: String): Future[Seq[FileStat]] =
    client.call[FileStats]("Contester.GridfsCopy",
      CopyOperations(entries = operations, sandboxId = sandboxId),
      FileStats).map(_.entries)

  def gridfsPut(names: Seq[(String, String)], sandboxId: String) = {
    val operations = names.map {
      case (source, destination) =>
        CopyOperation(localFileName = destination, remoteLocation = source, upload = false)
    }
    gridfsCopy(operations, sandboxId)
  }

  def gridfsGet(names: Seq[GridfsGetEntry], sandboxId: String) =
    gridfsCopy(names.map(_.toCopyOperation), sandboxId)

  def executeConnected(first: LocalExecutionParameters, second: LocalExecutionParameters) =
    client.call("Contester.LocalExecuteConnected",
      LocalExecuteConnected(first = Some(first), second = Some(second)),
      LocalExecuteConnectedResult)

}
