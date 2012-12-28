package org.stingray.contester.invokers

import collection.mutable
import com.twitter.util.{Duration, Future, Promise}
import org.stingray.contester.utils.Utils
import java.util.concurrent.TimeUnit
import grizzled.slf4j.Logging

trait HasCaps[CapsType] {
  def caps: Iterable[CapsType]
}

// Transient error
class TransientError(cause: RuntimeException) extends RuntimeException(cause)

// Permanent error: invoker is bad
trait PermanentError extends RuntimeException

// Too many errors
class TooManyErrors(cause: RuntimeException) extends RuntimeException(cause)


trait NewRequestStore[CapsType, KeyType <: Ordered[KeyType], InvokerType <: HasCaps[CapsType]] extends Logging {
  type QueueEntry = (KeyType, Promise[InvokerType], AnyRef)
  object entryOrdering extends Ordering[QueueEntry] {
    def compare(x: QueueEntry, y: QueueEntry): Int =
      x._1.compare(y._1)
  }

  val waiting = new mutable.HashMap[CapsType, mutable.PriorityQueue[QueueEntry]]()

  val freelist = mutable.Set[InvokerType]()
  val badlist = mutable.Set[InvokerType]()
  val uselist = new mutable.HashMap[InvokerType, (KeyType, AnyRef)]()

  def stillAlive(invoker: InvokerType): Boolean

  private[this] def retryOrThrow[X](cap: CapsType, schedulingKey: KeyType, retries: Option[Int], e: RuntimeException, f: InvokerType => Future[X]): Future[X] =
    if (!retries.exists(_ > 0))
      Future.exception(new TooManyErrors(e))
    else
      Utils.later(Duration(2, TimeUnit.SECONDS))
        .flatMap(_ => get(cap, schedulingKey, retries.map(_ - 1))(f))

  def get[X](cap: CapsType, schedulingKey: KeyType, extra: AnyRef, retries: Option[Int] = Some(5))(f: InvokerType => Future[X]): Future[X] =
    getInvoker(cap, schedulingKey, extra).flatMap { invoker =>
      trace("Using " + invoker)
      f(invoker)
        .rescue {
        case e: TransientError => {
          reuseInvoker(invoker)
          retryOrThrow(cap, schedulingKey, retries, e, f)
        }
        case e: PermanentError => {
          badInvoker(invoker)
          retryOrThrow(cap, schedulingKey, retries, e, f)
        }
        case e: Throwable => {
          reuseInvoker(invoker)
          Future.exception(e)
        }
      }.onSuccess(_ => reuseInvoker(invoker))
    }

  def getInvoker(cap: CapsType, schedulingKey: KeyType, extra: AnyRef): Future[InvokerType] =
    synchronized {
      freelist.find(_.caps.exists(_ == cap)).map { i =>
        freelist.remove(i)
        uselist(i) = (schedulingKey, extra)
        Future.value(i)
      }.getOrElse {
        val p = new Promise[InvokerType]()
        waiting.getOrElseUpdate(cap, new mutable.PriorityQueue[QueueEntry]()(entryOrdering)).enqueue((schedulingKey, p, extra))
        p
      }
    }

  def addInvokers(invokers: Iterable[InvokerType]): Unit =
    synchronized {
      invokers.foreach(addInvoker)
    }

  def addInvoker(invoker: InvokerType): Unit =
    if (stillAlive(invoker)) {
      trace("Adding " + invoker)
      waiting.filterKeys(invoker.caps.toSet).values.flatMap(w => w.headOption.map(_ -> w)).toSeq
        .sortBy(_._1._1).headOption.map { candidate =>
        val result = candidate._2.dequeue()
        uselist(invoker) = result._1 -> result._3
        result._2.setValue(invoker)
      }.getOrElse {
        freelist += invoker
      }
    }

  def reuseInvoker(invoker: InvokerType): Unit =
    synchronized {
      trace("Returning " + invoker)
      uselist.remove(invoker).foreach { _ =>
        addInvoker(invoker)
      }
    }


  def badInvoker(invoker: InvokerType): Unit =
    synchronized {
      uselist.remove(invoker).foreach { _ =>
        if (stillAlive(invoker))
          badlist += invoker
      }
    }

  def removeInvokers(invokers: Iterable[InvokerType]): Unit =
    synchronized {
      invokers.foreach { i =>
        freelist.remove(i)
        badlist.remove(i)
      }
    }
}
