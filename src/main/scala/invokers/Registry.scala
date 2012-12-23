package org.stingray.contester.invokers

import org.stingray.contester.rpc4.{ChannelDisconnectedException, RpcClient, Registry}
import grizzled.slf4j.Logging
import collection.mutable
import org.jboss.netty.channel.{ChannelHandler, Channel}
import org.stingray.contester._
import com.twitter.util.Future
import scala.Some
import org.stingray.contester.modules.ModuleFactoryFactory

class InvokerRegistry(mongoHost: String) extends Registry with Logging {
  private[this] val channelMap = new mutable.HashMap[Channel, InvokerBig]

  private[this] def configure(client: InvokerRpcClient) =
    client.identify("palevo", mongoHost, "contester")
      .map(new InvokerId(_, client)).flatMap { clientId =>
      ModuleFactoryFactory(clientId).map { moduleFactory =>
        new InvokerBig(clientId, moduleFactory)
      }
    }

  private[this] def register(invoker: InvokerBig) = {
    synchronized {
      channelMap.put(invoker.channel, invoker)
    }
    invoker.instances.foreach(addInvoker(_))
  }

  def register(channel: Channel): ChannelHandler = {
    val result = new RpcClient(channel)
    info("New channel: " + channel)
    configure(new InvokerRpcClient(result)).map(register(_))
    result
  }

  def unregister(channel: Channel): Unit = {
    synchronized {
      channelMap.remove(channel).map{ inv =>
        info("Lost channel: " + inv.i.name)
        inv.instances.foreach(invokers.remove(_))
      }
    }
  }

  private[this] val requests = new RequestStore
  private[this] val invokers = new InvokerStore

  private[this] val inUse = mutable.Map[InvokerInstance, (SchedulingKey, AnyRef)]()

  private[this] def internalStats = {
    val f = invokers.freelist.size
    val w = requests.waiting.filter(_._2.size > 0).map(x => x._1 + ":" + x._2.size).mkString(" ")
    val b = invokers.badList.size
    info("Waiting: %s, Free: %d, Bad: %d".format(w, f, b))
  }

  private[this] def bad(i: InvokerInstance, e: Throwable) = {
    error("Exception in invoker: " + i, e)
    synchronized {
      if (channelMap.contains(i.invoker.channel))
        invokers.bad(i)
    }
  }

  private[this] def getInvoker(m: String, key: SchedulingKey): Future[InvokerInstance] =
    synchronized {
      val r = invokers.get(m).map(Future.value(_))
        .getOrElse(requests.put(m, key))
      r
    }

  // TODO: Audit exceptions

  private[this] def wrappedGet[T](m: String, key: SchedulingKey, extra: AnyRef)(f: InvokerInstance => Future[T]): Future[T] =
    getInvoker(m, key).flatMap { i =>
      inUse.synchronized(inUse(i) = (key, extra))
      f(i).ensure(inUse.synchronized(inUse.remove(i)))
        .rescue {
        case e: ChannelDisconnectedException =>
          wrappedGet(m, key, extra)(f)
        case e: RestartException =>
          error("Restartable", e)
          wrappedGet(m, key, extra)(f)
        case e: InvokerBadException =>
          bad(i, e)
          wrappedGet(m, key, extra)(f)
      }.ensure(addInvoker(i))
    }

  def wrappedGetClear[T](m: String, key: SchedulingKey, extra: AnyRef)(f: InvokerInstance => Future[T]): Future[T] =
    wrappedGet[T](m, key, extra)(i => i.clear.flatMap(f(_)))

  private[this] def addInvoker(inv: InvokerInstance): Unit =
    synchronized {
      if (channelMap.contains(inv.invoker.channel)) {
        requests.get(inv.caps).map(_.setValue(inv)).getOrElse(invokers.put(inv))
      }
    }

  def getWaiting: Iterable[(String, (Int, Iterable[(SchedulingKey, Int)]))] =
    requests.waiting.synchronized {
      requests.waiting.mapValues { items =>
        val keyMap = mutable.Map[SchedulingKey, Int]()
        items.foreach(x => keyMap.update(x._1, keyMap.getOrElse(x._1, 0) + 1))
        (items.size, keyMap.toSeq.sortBy(_._1))
      }
    }.filter(_._2._2.nonEmpty).toSeq.sortBy(-_._2._1)

  def getInvokers =
    synchronized {
      channelMap.values.toSeq.sortBy(_.i.name).map { i =>
        (i -> i.instances.map { k =>
          k -> (if (invokers.freelist(k)) ("F", None) else if (invokers.badList(k)) ("B", None) else inUse.get(k).map(x => ("U", Some(x))).getOrElse(("?", None)))
        })
      }
    }
}
