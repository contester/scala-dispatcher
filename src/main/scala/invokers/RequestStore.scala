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

/**
 * Brains behind InvokerRegistry.
 * @tparam CapsType Type for capability ids.
 * @tparam KeyType Type for keys.
 * @tparam InvokerType Type for invokers. HasCaps[CapsType]
 */
trait RequestStore[CapsType, KeyType <: Ordered[KeyType], InvokerType <: HasCaps[CapsType]] extends Logging {
  protected type QueueEntry = (KeyType, Promise[InvokerType], AnyRef)
  private[this] object entryOrdering extends Ordering[QueueEntry] {
    def compare(x: QueueEntry, y: QueueEntry): Int =
      y._1.compare(x._1)
  }

  protected val waiting = new mutable.HashMap[CapsType, mutable.PriorityQueue[QueueEntry]]()

  protected val freelist = mutable.Set[InvokerType]()
  protected val badlist = mutable.Set[InvokerType]()
  protected val uselist = new mutable.HashMap[InvokerType, (KeyType, AnyRef)]()

  protected def stillAlive(invoker: InvokerType): Boolean

  private[this] def retryOrThrow[X](cap: CapsType, schedulingKey: KeyType, retries: Option[Int], e: RuntimeException, f: InvokerType => Future[X]): Future[X] =
    if (!retries.exists(_ > 0))
      Future.exception(new TooManyErrors(e))
    else
      Utils.later(Duration(2, TimeUnit.SECONDS))
        .flatMap(_ => get(cap, schedulingKey, retries.map(_ - 1))(f))

  def get[X](cap: CapsType, schedulingKey: KeyType, extra: AnyRef, retries: Option[Int] = Some(5))(f: InvokerType => Future[X]): Future[X] =
    getInvoker(cap, schedulingKey, extra).flatMap { invoker =>
      trace("Using %s for %s".format(invoker, schedulingKey))
      f(invoker)
        .onSuccess(_ => reuseInvoker(invoker, schedulingKey))
        .rescue {
        case e: TransientError => {
          error("Transient error:", e)
          reuseInvoker(invoker, schedulingKey)
          retryOrThrow(cap, schedulingKey, retries, e, f).map { tresult =>
            trace("Traced recovery after transient error")
            tresult
          }
        }
        case e: PermanentError => {
          error("Permanent error:", e)
          badInvoker(invoker)
          retryOrThrow(cap, schedulingKey, retries, e, f)
        }
        case e: Throwable => {
          error("Unknown error:", e)
          reuseInvoker(invoker, schedulingKey)
          Future.exception(e)
        }
      }
    }

  private[this] def getInvoker(cap: CapsType, schedulingKey: KeyType, extra: AnyRef): Future[InvokerType] =
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

  /**
   * Remove empty queues for caps.
   */
  private def discardEmptyQueues(): Unit =
    synchronized {
      waiting.filter(_._2.isEmpty).foreach(k => waiting.remove(k._1))
    }

  /**
   * Re-add invoker to the system, marking it as available or immediately scheduling the work on it.
   * @param invoker Invoker to add.
   */
  private[this] def addInvoker(invoker: InvokerType): Unit =
    if (stillAlive(invoker)) {
      trace("Adding " + invoker)
      waiting.filterKeys(invoker.caps.toSet).values.flatMap(w => w.headOption.map(_ -> w)).toSeq
        .sortBy(_._1._1).headOption.map { candidate =>
        val result = candidate._2.dequeue()
        discardEmptyQueues() // TODO: run this asynchronously and not that often
        uselist(invoker) = result._1 -> result._3
        result._2.setValue(invoker)
      }.getOrElse {
        freelist += invoker
      }
    }

  /**
   * Re-add invoker after being used by schedulingKey. Mark it as not used first, then re-add.
   * @param invoker Invoker to re-add
   * @param schedulingKey
   */
  private[this] def reuseInvoker(invoker: InvokerType, schedulingKey: KeyType): Unit =
    synchronized {
      uselist.remove(invoker).foreach { _ =>
        trace("Returning %s after %s".format(invoker, schedulingKey))
        addInvoker(invoker)
      }
    }

  /**
   * Mark invoker as bad.
   * @param invoker
   */
  private[this] def badInvoker(invoker: InvokerType): Unit =
    synchronized {
      uselist.remove(invoker).foreach { _ =>
        if (stillAlive(invoker))
          badlist += invoker
      }
    }

  /**
   * Remove invokers.
   * @param invokers
   */
  protected def removeInvokers(invokers: Iterable[InvokerType]): Unit =
    synchronized {
      invokers.foreach { i =>
        freelist.remove(i)
        badlist.remove(i)
      }
    }
}
