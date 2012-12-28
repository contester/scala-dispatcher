package org.stingray.contester.invokers

import org.stingray.contester.rpc4.{RpcClient, Registry}
import grizzled.slf4j.Logging
import collection.mutable
import org.jboss.netty.channel.{ChannelHandler, Channel}
import com.twitter.util.Future
import scala.Some
import org.stingray.contester.modules.ModuleFactoryFactory

class InvokerRegistry(mongoHost: String) extends Registry with NewRequestStore[String, SchedulingKey, InvokerInstance] with Logging {
  private[this] val channelMap = new mutable.HashMap[Channel, InvokerBig]

  private[this] def configure(client: InvokerRpcClient) =
    client.identify("palevo", mongoHost, "contester")
      .map(new InvokerId(_, client)).flatMap { clientId =>
      ModuleFactoryFactory(clientId).map { moduleFactory =>
        new InvokerBig(clientId, moduleFactory)
      }
    }

  private[this] def register(invoker: InvokerBig): Unit = {
    synchronized {
      channelMap.put(invoker.channel, invoker)
    }
    addInvokers(invoker.instances)
  }

  def register(channel: Channel): ChannelHandler = {
    val result = new RpcClient(channel)
    info("New channel: " + channel)
    configure(new InvokerRpcClient(result)).map(register(_))
    result
  }

  def unregister(channel: Channel): Unit = {
    synchronized {
      channelMap.remove(channel)
    }.foreach { inv =>
        info("Lost channel: " + inv.i.name)
        removeInvokers(inv.instances)
      }
    }

  def stillAlive(invoker: InvokerInstance) =
    channelMap.contains(invoker.invoker.channel)

  def apply[T](m: String, key: SchedulingKey, extra: AnyRef)(f: InvokerInstance => Future[T]): Future[T] =
    get[T](m, key, extra)(i => i.clear.flatMap(f(_)))

  def getWaiting: Iterable[(String, (Int, Iterable[(SchedulingKey, Int)]))] =
    synchronized {
      waiting.mapValues { items =>
        val keyMap = mutable.Map[SchedulingKey, Int]()
        items.foreach(x => keyMap.update(x._1, keyMap.getOrElse(x._1, 0) + 1))
        (items.size, keyMap.toSeq.sortBy(_._1))
      }
    }.filter(_._2._2.nonEmpty).toSeq.sortBy(-_._2._1)

  def getInvokers =
    synchronized {
      channelMap.values.toSeq.sortBy(_.i.name).map { i =>
        (i -> i.instances.map { k =>
          k -> (if (freelist(k)) ("F", None) else if (badlist(k)) ("B", None) else uselist.get(k).map(x => ("U", Some(x))).getOrElse(("?", None)))
        })
      }
    }
}
