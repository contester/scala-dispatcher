package org.stingray.contester.invokers

import collection.mutable
import com.twitter.util.Promise

// todo: refactor both stores into single data structure

class RequestStore {
  val waiting = new mutable.HashMap[String, mutable.Set[(SchedulingKey, Promise[InvokerInstance])]]()

  def get(caps: Iterable[String]) =
    synchronized {
      val candidates = caps.flatMap(waiting.getOrElse(_, Nil)).toSeq
      val candidate = candidates.sortBy(_._1).headOption
      candidate.foreach { x =>
        caps.foreach { c =>
          waiting.get(c).foreach(_.remove(x))
        }
      }
      candidate.map(_._2)
    }

  def put(caps: String, key: SchedulingKey) = {
    val p = new Promise[InvokerInstance]()
    synchronized {
      waiting.getOrElseUpdate(caps, new mutable.HashSet[(SchedulingKey, Promise[InvokerInstance])]()).add((key, p))
    }
    p
  }
}

class InvokerStore {
  val freelist = mutable.Set[InvokerInstance]()
  val badList = mutable.Set[InvokerInstance]()

  def get(m: String) =
    synchronized {
      freelist.find(_.caps(m)).map { i =>
        freelist.remove(i)
        i
      }
    }

  def put(i: InvokerInstance) =
    synchronized {
      freelist.add(i)
    }

  def bad(i: InvokerInstance) =
    synchronized {
      badList.add(i)
    }

  def remove(i: InvokerInstance) =
    synchronized {
      freelist.remove(i)
      badList.remove(i)
    }
}
