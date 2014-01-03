package org.stingray.contester.invokers

import com.twitter.util.Future
import org.stingray.contester.proto.Local._
import org.stingray.contester.rpc4.RpcClient
import org.stingray.contester.proto.Blobs.FileBlob

class GridfsGetEntry(val local: String, val remote: String, val moduleType: Option[String])

class PureInvokerRpcClient(val channel: RpcClient) extends InvokerRpcClient

trait InvokerRpcClient {
  def channel: RpcClient

  def getBinaryType(pathname: String) =
    channel.call[BinaryTypeResponse]("Contester.GetBinaryType",
      BinaryTypeRequest.newBuilder().setPathname(pathname).build())

  def execute(params: LocalExecutionParameters) =
    channel.call[LocalExecutionResult]("Contester.LocalExecute", params)

  def clear(sandbox: String) =
    channel.callNoResult("Contester.Clear", ClearSandboxRequest.newBuilder().setSandbox(sandbox).build())

  def put(file: FileBlob): Future[FileStat] =
    channel.call[FileStat]("Contester.Put", file)

  def get(name: String) =
    channel.call[FileBlob]("Contester.Get", GetRequest.newBuilder().setName(name).build())

  def fileStat(names: Iterable[String], expand: Boolean, sandboxId: Option[String], calculateChecksum: Boolean) = {
    import collection.JavaConversions.asJavaIterable
    val v = StatRequest.newBuilder().addAllName(names).setExpand(expand)
    sandboxId.foreach(v.setSandboxId)
    if (calculateChecksum)
      v.setCalculateChecksum(calculateChecksum)
    channel.call[FileStats]("Contester.Stat", v.build())
  }.map {
    import collection.JavaConverters._
    _.getEntriesList.asScala
  }

  def identify(contesterId: String, mHost: String, mDb: String) =
    channel.call[IdentifyResponse]("Contester.Identify",
      IdentifyRequest.newBuilder().setContesterId(contesterId).setMongoHost(mHost).setMongoDb(mDb).build())

  def gridfsCopy(operations: Iterable[CopyOperation], sandboxId: String): Future[Iterable[FileStat]] = {
    import collection.JavaConversions.asJavaIterable
    val request = CopyOperations.newBuilder().setSandboxId(sandboxId).addAllEntries(operations).build()
    channel.call[FileStats]("Contester.GridfsCopy", request).map { entries =>
      import collection.JavaConverters._
      entries.getEntriesList.asScala
    }
  }

  def gridfsPut(names: Iterable[(String, String)], sandboxId: String) = {
    val operations = names.map {
      case (source, destination) =>
        CopyOperation.newBuilder().setLocalFileName(destination).setRemoteLocation(source).setUpload(false).build()
    }
    gridfsCopy(operations, sandboxId)
  }

  def gridfsGet(names: Iterable[GridfsGetEntry], sandboxId: String) = {
    val operations = names.map {
      entry =>
        val builder = CopyOperation.newBuilder()
          .setLocalFileName(entry.local)
          .setRemoteLocation(entry.remote)
          .setUpload(true)
        entry.moduleType.foreach(builder.setModuleType(_))
        builder.build()
    }
    gridfsCopy(operations, sandboxId)
  }

  def executeConnected(first: LocalExecutionParameters, second: LocalExecutionParameters) =
    channel.call[LocalExecuteConnectedResult]("Contester.LocalExecuteConnected",
      LocalExecuteConnected.newBuilder().setFirst(first).setSecond(second).build())
}
