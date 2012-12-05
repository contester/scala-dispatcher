package org.stingray.contester.invokers

import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.proto.Local._
import org.stingray.contester.rpc4.{RemoteError, RpcClient}
import org.stingray.contester.proto.Blobs.FileBlob

class RestartException(a: Throwable) extends Throwable(a)

object InvokerRpcClient {
  def newNamePairs(x: Iterable[(String, String)]) =
    x.map(v => NamePair.newBuilder().setSource(v._1).setDestination(v._2).build())

  def newNamePairRequest(x: Iterable[(String, String)], sandboxId: String) = {
    import collection.JavaConversions.asJavaIterable
    RepeatedNamePairEntries.newBuilder().addAllEntries(newNamePairs(x)).setSandboxId(sandboxId).build()
  }

  def repeatedStringEntriesAsScala(x: RepeatedStringEntries) = {
    import collection.JavaConverters._
    x.getEntriesList.asScala
  }
}

class InvokerRpcClient(val client: RpcClient) extends Logging {

  import InvokerRpcClient._

  val channel = client.channel

  // TODO: use this
  def getBinaryType(pathname: String) =
    client.call[BinaryTypeResponse]("Contester.GetBinaryType", BinaryTypeRequest.newBuilder().setPathname(pathname).build())

  def execute(params: LocalExecutionParameters) = {
    trace("LocalExecute: " + params)
    client.call[LocalExecutionResult]("Contester.LocalExecute", params)
      .onSuccess(x => trace("LocalExecuteResult: " + x))
      .onFailure(x => error("LocalExecute: ", x))
  }

  def clear(sandbox: String) = {
    trace("Clear: " + sandbox)
    client.callNoResult("Contester.Clear", ClearSandboxRequest.newBuilder().setSandbox(sandbox).build())
      .onFailure(x => error("Clear: ", x))
  }

  def put(file: FileBlob): Future[Unit] = {
    trace("Put: " + file.getName)
    client.callNoResult("Contester.Put", file)
      .onFailure(x => error("Put: ", x))
  }

  def get(name: String) = {
    trace("Get: " + name)
    client.call[FileBlob]("Contester.Get", GetRequest.newBuilder().setName(name).build())
      .onSuccess(x => trace("GetResult: " + x.getName))
      .onFailure(x => error("GetResult: ", x))
  }

  def fileStat(names: Iterable[String], expand: Boolean, sandboxId: Option[String]) = {
    trace("Stat: " + (names, expand, sandboxId))
    import collection.JavaConversions.asJavaIterable
    val v = StatRequest.newBuilder().addAllName(names).setExpand(expand)
    sandboxId.foreach(v.setSandboxId(_))
    client.call[FileStats]("Contester.Stat", v.build())
  }.map {
    import collection.JavaConverters._
    _.getStatsList.asScala
  }
    .onSuccess(x => trace("StatResult: " + x))
    .onFailure(x => error("Stat: ", x))

  def identify(contesterId: String, mHost: String, mDb: String) = {
    trace("Identify: " + (contesterId, mHost, mDb))
    client.call[IdentifyResponse]("Contester.Identify", IdentifyRequest.newBuilder().setContesterId(contesterId).setMongoHost(mHost).setMongoDb(mDb).build())
      .onSuccess(x => trace("IdentifyResult: " + x))
      .onFailure(x => error("Identify: ", x))
      .handle {
      case e: RemoteError => throw new InvokerBadException(e)
    }

  }

  def gridfsPut(names: Iterable[(String, String)], sandboxId: String) = {
    trace("GridfsPut: " + (names, sandboxId))
    client.call[RepeatedStringEntries]("Contester.GridfsPut", newNamePairRequest(names, sandboxId)).map(repeatedStringEntriesAsScala)
      .onSuccess(x => trace("GridfsPutResult: " + x))
      .onFailure(x => error("GridfsPut: ", x))
      .handle {
      case e: RemoteError => throw new RestartException(e)
    }
  }

  def gridfsGet(names: Iterable[(String, String)], sandboxId: String) = {
    trace("GridfsGet: " + (names, sandboxId))
    client.call[RepeatedStringEntries]("Contester.GridfsGet", newNamePairRequest(names, sandboxId)).map(repeatedStringEntriesAsScala)
      .onSuccess(x => trace("GridfsGetResult: " + x))
      .onFailure(x => error("GridfsGet: ", x))
      .handle {
      case e: RemoteError => throw new RestartException(e)
    }
  }

  def executeConnected(first: LocalExecutionParameters, second: LocalExecutionParameters) = {
    trace("LocalExecuteConnected:" + (first, second))
    client.call[LocalExecuteConnectedResult]("Contester.LocalExecuteConnected", LocalExecuteConnected.newBuilder().setFirst(first).setSecond(second).build())
      .onSuccess(x => trace("LocalExecuteConnected: " + x))
      .onFailure(x => error("LocalExecuteConnected", x))
      .handle {
      case e: RemoteError => throw new RestartException(e)
    }
  }
}
