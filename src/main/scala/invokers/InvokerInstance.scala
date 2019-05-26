package org.stingray.contester.invokers

import com.twitter.util.Future
import org.stingray.contester.modules.SpecializedModuleFactory
import org.stingray.contester.proto.SandboxLocations

class InvokerInstance(val invoker: Invoker, val index: Int, val data: SandboxLocations) extends HasCaps[String] {
  val restricted: Sandbox = new Sandbox(this, true, invoker.api.file(data.run))
  val unrestricted: Sandbox = new Sandbox(this, false, invoker.api.file(data.compile))
  val caps: Set[String] = invoker.caps
  val name: String = invoker.api.name + "." + index
  val factory: SpecializedModuleFactory = invoker.moduleFactory

  override def toString =
    name

  def clear: Future[InvokerInstance] =
    restricted.clear.join(
      unrestricted.clear)
      .map(_ => this)
}

