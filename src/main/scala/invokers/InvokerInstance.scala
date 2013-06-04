package org.stingray.contester.invokers

import grizzled.slf4j.Logging
import com.twitter.util.Future
import org.stingray.contester.modules.ModuleFactory

trait FactoryInstance {
  def factory: ModuleFactory
  // def platform: String
}

trait CompilerInstance extends FactoryInstance {
  def comp: Sandbox
}

trait RunnerInstance extends FactoryInstance {
  def run: Sandbox
}

class InvokerInstance(val invoker: InvokerBig, val instanceId: Int) extends Logging with CompilerInstance with RunnerInstance with HasCaps[String] {
  val data = invoker.i.sandboxes(instanceId)
  val run = new Sandbox(this, true)
  val comp = new Sandbox(this, false)
  val caps = invoker.caps
  val name = invoker.i.name + "." + instanceId
  val factory = invoker.moduleFactory
  //val platform = invoker.i.platform

  override def toString =
    name

  def clear: Future[InvokerInstance] =
    run.clear.join(comp.clear).map(_ => this)
}

