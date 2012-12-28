package org.stingray.contester.invokers

import collection.mutable
import com.twitter.util.{Duration, Future, Promise}
import org.stingray.contester.utils.Utils
import java.util.concurrent.TimeUnit

trait HasCaps[CapsType] {
  def caps: Iterable[CapsType]
}

// Transient error
class TransientError(cause: RuntimeException) extends RuntimeException(cause)

// Permanent error: invoker is bad
trait PermanentError extends RuntimeException

// Too many errors
class TooManyErrors(cause: RuntimeException) extends RuntimeException(cause)


trait NewRequestStore[CapsType, KeyType <: Ordered[KeyType], InvokerType <: HasCaps[CapsType]] {
  val waiting = new mutable.HashMap[CapsType, mutable.Set[(KeyType, Promise[InvokerType])]]()

  val freelist = mutable.Set[InvokerType]()
  val badlist = mutable.Set[InvokerType]()
  val uselist = new mutable.HashMap[InvokerType, (KeyType, AnyRef)]()

  def stillAlive(invoker: InvokerType): Boolean

  private[this] def retryOrThrow[X](cap: CapsType, schedulingKey: KeyType, retries: Option[Int], e: RuntimeException, f: InvokerType => Future[X]): Future[X] =
    if (!retries.exists(_ > 0))
      Future.exception(new TooManyErrors(e))
    else
      Utils.later(Duration(2, TimeUnit.SECONDS))
        .flatMap(_ => apply(cap, schedulingKey, retries.map(_ - 1))(f))

  def apply[X](cap: CapsType, schedulingKey: KeyType, extra: AnyRef, retries: Option[Int] = Some(5))(f: InvokerType => Future[X]): Future[X] =
    getInvoker(cap, schedulingKey, extra).flatMap { invoker =>
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
        waiting.getOrElseUpdate(cap, new mutable.HashSet[(KeyType, Promise[InvokerType])]()).add(schedulingKey -> p)
        p
      }
    }

  def addInvokers(invokers: Iterable[InvokerType]): Unit =
    synchronized {
      invokers.foreach(addInvoker)
    }

  def addInvoker(invoker: InvokerType): Unit =
    if (stillAlive(invoker)) {
      invoker.caps.flatMap { cap =>
        val w: mutable.Set[(KeyType, Promise[InvokerType])] = waiting.getOrElse(cap, mutable.Set())
        w.map(x => x -> w)
      }.toSeq.sortBy(_._1._1).headOption.map { candidate =>
        candidate._2.remove(candidate._1)
        candidate._1._2.setValue(invoker)
      }.getOrElse {
        freelist += invoker
      }
    }

  def reuseInvoker(invoker: InvokerType): Unit =
    synchronized {
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
