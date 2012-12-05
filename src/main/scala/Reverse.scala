package org.stingray.contester

import ContesterImplicits._
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common._
import proto.Blobs.{FileBlob, Blob, Module}
import proto.Local._
import rpc4.RemoteError
import org.stingray.contester.utils.{CommandLineTools, ExecutionArguments, LocalEnvironmentTools}
import org.stingray.contester.invokers.InvokerRpcClient

class InvokerBadException(e: Throwable) extends scala.Throwable(e)
class SandboxClearException(e: Throwable) extends InvokerBadException(e)

class Sandbox(val instance: InvokerInstance, val restricted: Boolean)  {
  val invoker = instance.invoker
  val i = invoker.i
  val path = i.file(if (restricted) instance.data.getRun else instance.data.getCompile)
  val sandboxId = i.file("%" + instance.instanceId + "." + (if (restricted) "R" else "C"))

  val localEnvironment = if (restricted) i.cleanedLocalEnvironment else i.localEnvWithPath

  def put(module: Module): Future[Unit] =
    put(module.getData, module.getName)

  def put(blob: Blob, name: String): Future[Unit] =
    i.put(sandboxId ** name, blob)

  def put(module: Module, name: String): Future[Unit] =
    put(module.setName(name))

  private def putGridfs(source: String, dest: RemoteFile): Future[Option[String]] =
    i.putGridfs(Seq(source -> dest), sandboxId.name).map(_.headOption)

  def putGridfs(source: String, dest: String): Future[Option[String]] =
    putGridfs(source, sandboxId ** dest)

  def getGridfs(items: Iterable[(RemoteFile, String)]) =
    i.getGridfs(items, sandboxId.name)

  def getModule(f: RemoteFile): Future[Module] =
    i.get(f).map(v => Modules(v.getData).setName(f.basename).setTypeFromName)

  def getModule(name: String): Future[Module] =
    getModule(sandboxId ** name)

  def stat(f: Iterable[RemoteFile]): Future[Iterable[RemoteFile]] =
    i.stat(f)

  def glob(f: Iterable[RemoteFile]) =
    i.glob(f)

  def stat(name: String): Future[Iterable[RemoteFile]] =
    stat(sandboxId ** name :: Nil)

  def statFile(name: String) =
    stat(name).map(_.isFile)

  def getModuleOption(f: RemoteFile): Future[Option[Module]] =
    getModule(f).map(Some(_)).handle {
      case e: RemoteError => None
    }

  def clear = i.rpc.clear(sandboxId.name).handle {
    case e: RemoteError => throw new SandboxClearException(e)
  }

  def getExecutionParameters(filename: String, arguments: ExecutionArguments): Future[LocalExecutionParameters] =
    Future.value(
      CommandLineTools.fillCommandLine(filename, arguments)
        .setEnvironment(localEnvironment)
        .setSandboxId(sandboxId.name)
        .setCurrentAndTemp(path.name))

  def execute(params: LocalExecutionParameters) =
    invoker.i.execute(params)

  def executeWithParams(params: LocalExecutionParameters) =
    execute(params).map(params -> _)
}

trait FactoryInstance {
  def factory: ModuleFactory
  def platform: String
}

trait CompilerInstance extends FactoryInstance {
  def comp: Sandbox
}

trait RunnerInstance extends FactoryInstance {
  def run: Sandbox
}

class InvokerInstance(val invoker: InvokerBig, val instanceId: Int) extends Logging with CompilerInstance with RunnerInstance {
  val data = invoker.i.sandboxes(instanceId)
  val run = new Sandbox(this, true)
  val comp = new Sandbox(this, false)
  val caps = invoker.caps
  val name = invoker.i.name + "." + instanceId
  val factory = invoker.moduleFactory
  val platform = invoker.i.platform

  override def toString =
    name

  def clear: Future[InvokerInstance] =
    run.clear.join(comp.clear).map(_ => this)
}

class InvokerId(val clientId: IdentifyResponse, val rpc: InvokerRpcClient) {

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

  def fileStat(what: Iterable[RemoteFile], expand: Boolean, sandboxId: Option[String]) =
    rpc.fileStat(what.map(_.name), expand, sandboxId).map(_.map(file))

  def glob(what: Iterable[RemoteFile]): Future[Iterable[RemoteFile]] =
    fileStat(what, true, None)

  def stat(what: Iterable[RemoteFile]): Future[Iterable[RemoteFile]] =
    fileStat(what, false, None)

  def get(file: RemoteFile): Future[FileBlob] =
    rpc.get(file.name)

  def put(file: RemoteFile, blob: Blob) =
    rpc.put(FileBlob.newBuilder().setName(file.name).setData(blob).build())

  def putGridfs(items: Iterable[(String, RemoteFile)], sandboxId: String): Future[Iterable[String]] =
    rpc.gridfsPut(items.map(m => m._1 -> m._2.name), sandboxId)

  def getGridfs(items: Iterable[(RemoteFile, String)], sandboxId: String): Future[Iterable[String]] =
    rpc.gridfsGet(items.map(m => m._1.name -> m._2), sandboxId)

  def executeConnected(first: LocalExecutionParameters, second: LocalExecutionParameters): Future[(LocalExecutionResult, LocalExecutionResult)] =
    rpc.executeConnected(first, second).map(x => (x.getFirst, x.getSecond))
}

class InvokerBig(val i: InvokerId, val moduleFactory: ModuleFactory) extends Logging {
  val caps = moduleFactory.moduleTypes.toSet
  val instances = (0 to (i.sandboxes.length - 1)).map(new InvokerInstance(this, _))
  val channel = i.channel
}



