package org.stingray.contester.invokers

import com.twitter.util.Future
import org.stingray.contester.proto._
import org.stingray.contester.rpc4.RpcClient

case class GridfsGetEntry(local: String, remote: String, moduleType: Option[String]) {
  def toCopyOperation =
    CopyOperation(localFileName = Some(local), remoteLocation = Some(remote), upload = Some(true), moduleType=moduleType)
}

class InvokerRpcClient(val client: RpcClient) {
  def getBinaryType(pathname: String): Future[BinaryTypeResponse] =
    client.call("Contester.GetBinaryType",
      BinaryTypeRequest(pathname = Some(pathname)),
      BinaryTypeResponse.parseFrom)

  def execute(params: LocalExecutionParameters): Future[LocalExecutionResult] =
    client.call("Contester.LocalExecute", params, LocalExecutionResult.parseFrom)

  def clear(sandbox: String) =
    client.callNoResult("Contester.Clear", ClearSandboxRequest(sandbox = Some(sandbox)))

  def put(file: FileBlob): Future[FileStat] =
    client.call("Contester.Put", file, FileStat.parseFrom)

  def get(name: String) =
    client.call("Contester.Get", GetRequest(name), FileBlob.parseFrom)

  def fileStat(names: Iterable[String], expand: Boolean, sandboxId: Option[String], calculateChecksum: Boolean) = {
    client.call("Contester.Stat",
      StatRequest(names.toSeq, sandboxId, expand=Some(expand), calculateChecksum=Some(calculateChecksum)),
      FileStats.parseFrom(_))
  }.map(_.entries)

  def identify(contesterId: String, mHost: String) =
    client.call("Contester.Identify",
      IdentifyRequest(contesterId = Some(contesterId)),
      IdentifyResponse.parseFrom(_))

  def gridfsCopy(operations: Seq[CopyOperation], sandboxId: String): Future[Seq[FileStat]] =
    client.call[FileStats]("Contester.GridfsCopy",
      CopyOperations(entries = operations, sandboxId = Some(sandboxId)),
      FileStats.parseFrom(_)).map(_.entries)

  def gridfsPut(names: Seq[(String, String)], sandboxId: String) = {
    val operations = names.map {
      case (source, destination) =>
        CopyOperation(localFileName = Some(destination), remoteLocation = Some(source), upload = Some(false))
    }
    gridfsCopy(operations, sandboxId)
  }

  def gridfsGet(names: Seq[GridfsGetEntry], sandboxId: String) =
    gridfsCopy(names.map(_.toCopyOperation), sandboxId)

  def executeConnected(first: LocalExecutionParameters, second: LocalExecutionParameters) =
    client.call("Contester.LocalExecuteConnected",
      LocalExecuteConnected(first = Some(first), second = Some(second)),
      LocalExecuteConnectedResult.parseFrom(_))

}
