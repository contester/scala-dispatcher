package org.stingray.contester.invokers

import org.stingray.contester.rpc4.{RpcClient, Registry}
import grizzled.slf4j.Logging
import collection.mutable
import com.twitter.util.Future
import org.stingray.contester.modules.ModuleFactory
import java.util.concurrent.ConcurrentHashMap

class InvokerRegistry(contesterId: String) extends Registry with RequestStore[String, SchedulingKey, InvokerInstance] with Logging {
  private[this] val channelMap = {
    import scala.collection.JavaConverters._
    new ConcurrentHashMap[RpcClient, Invoker]().asScala
  }

  def register(client: RpcClient): Unit = {
    trace(s"Registering client: ${client}")
    val invokerClient = new InvokerRpcClient(client)
    invokerClient.identify(contesterId)
      .flatMap { clientId =>
        trace(s"Client ID: $clientId")
      val api = new InvokerAPI(clientId, invokerClient)
      ModuleFactory(api)
        .onFailure(error("Module factory error", _))
        .map { factory =>
        new Invoker(api, factory)
      }
    }.map { invoker =>
      trace(s"Built invoker $invoker for client $client")
      channelMap.put(client, invoker)
      addInvokers(invoker.instances)
    }.onFailure(error("Register error", _))
  }

  def unregister(client: RpcClient): Unit = {
      channelMap.remove(client)
    .foreach { inv =>
        info(s"Lost channel: ${inv.api.name}")
        removeInvokers(inv.instances)
      }
    }

  def stillAlive(invoker: InvokerInstance): Boolean =
    channelMap.contains(invoker.invoker.api.client.client)

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
      channelMap.values.toSeq.sortBy(_.api.name).map { i =>
        (i -> i.instances.map { k =>
          k -> (if (freelist(k)) ("F", None) else if (badlist(k)) ("B", None) else uselist.get(k).map(x => ("U", Some(x))).getOrElse(("?", None)))
        })
      }
    }
}
