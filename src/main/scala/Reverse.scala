package org.stingray.contester

import ContesterImplicits._
import com.twitter.util.Future
import grizzled.slf4j.Logging
import org.stingray.contester.common._
import proto.Blobs.{Blob, Module}
import proto.Local._
import rpc4.RemoteError
import org.stingray.contester.utils.{CommandLineTools, ExecutionArguments}
import org.stingray.contester.invokers.InvokerBig

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


