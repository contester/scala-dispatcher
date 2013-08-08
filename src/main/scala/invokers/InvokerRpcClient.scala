package org.stingray.contester.invokers

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.proto.Local._
import org.stingray.contester.rpc4.{RemoteError, RpcClient}
import org.stingray.contester.proto.Blobs.FileBlob

class InvokerRpcClient(client: RpcClient) extends Logging {
  val channel = client.channel

  def getBinaryType(pathname: String) =
    client.call[BinaryTypeResponse]("Contester.GetBinaryType",
      BinaryTypeRequest.newBuilder().setPathname(pathname).build())

  def execute(params: LocalExecutionParameters) =
    client.call[LocalExecutionResult]("Contester.LocalExecute", params)

  def clear(sandbox: String) =
    client.callNoResult("Contester.Clear", ClearSandboxRequest.newBuilder().setSandbox(sandbox).build())

  def put(file: FileBlob): Future[Unit] =
    client.callNoResult("Contester.Put", file)

  def get(name: String) =
    client.call[FileBlob]("Contester.Get", GetRequest.newBuilder().setName(name).build())

  def fileStat(names: Iterable[String], expand: Boolean, sandboxId: Option[String], calculateSha1: Boolean) = {
    trace("Stat: " + (names, expand, sandboxId))
    import collection.JavaConversions.asJavaIterable
    val v = StatRequest.newBuilder().addAllName(names).setExpand(expand)
    sandboxId.foreach(v.setSandboxId(_))
    if (calculateSha1)
      v.setCalculateSha1(calculateSha1)
    client.call[FileStats]("Contester.Stat", v.build())
  }.map {
    import collection.JavaConverters._
    _.getStatsList.asScala
  }
    .onSuccess(x => trace("StatResult: " + x))
    .onFailure(x => error("Stat: ", x))

  def identify(contesterId: String, mHost: String, mDb: String) =
    client.call[IdentifyResponse]("Contester.Identify",
      IdentifyRequest.newBuilder().setContesterId(contesterId).setMongoHost(mHost).setMongoDb(mDb).build())

  def gridfsCopy(operations: Iterable[CopyOperation], sandboxId: String): Future[Iterable[CopyOperationResult]] = {
    import collection.JavaConversions.asJavaIterable
    val request = CopyOperations.newBuilder().setSandboxId(sandboxId).addAllEntries(operations).build()
    client.call[CopyOperationResults]("Contester.GridfsCopy", request).map { entries =>
      import collection.JavaConverters._
      entries.getEntriesList.asScala
    }
  }

  def gridfsPut(names: Iterable[(String, String)], sandboxId: String) = {
    val operations = names.map {
      case (source, destination) =>
        CopyOperation.newBuilder().setLocalFileName(destination).setRemoteLocation(source).setUpload(false).build()
    }
    gridfsCopy(operations, sandboxId).map(_.map(_.getLocalFileName))
  }

  def gridfsGet(names: Iterable[(String, String)], sandboxId: String) = {
    val operations = names.map {
      case (source, destination) =>
        CopyOperation.newBuilder().setLocalFileName(source).setRemoteLocation(destination).setUpload(true).build()
    }
    gridfsCopy(operations, sandboxId).map(_.map(_.getLocalFileName))
  }

  def executeConnected(first: LocalExecutionParameters, second: LocalExecutionParameters) = {
    trace("LocalExecuteConnected:" + (first, second))
    client.call[LocalExecuteConnectedResult]("Contester.LocalExecuteConnected", LocalExecuteConnected.newBuilder().setFirst(first).setSecond(second).build())
      .onSuccess(x => trace("LocalExecuteConnected: " + x))
      .onFailure(x => error("LocalExecuteConnected", x))
      .handle {
      case e: RemoteError => throw new TransientError(e)
    }
  }
}
