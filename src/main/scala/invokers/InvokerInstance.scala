package org.stingray.contester.invokers

import com.twitter.util.Future
import org.stingray.contester.proto.SandboxLocations

class InvokerInstance(val invoker: Invoker, val index: Int, val data: SandboxLocations) extends HasCaps[String] {
  val restricted = new Sandbox(this, true, invoker.api.file(data.run))
  val unrestricted = new Sandbox(this, false, invoker.api.file(data.compile))
  val caps = invoker.caps
  val name = invoker.api.name + "." + index
  val factory = invoker.moduleFactory

  override def toString =
    name

  def clear: Future[InvokerInstance] =
    restricted.clear.join(
      unrestricted.clear)
      .map(_ => this)
}

