package org.stingray.contester

import collection.mutable
import com.twitter.util.Promise
import java.sql.Timestamp
import org.stingray.contester.common.SubmitWithModule

trait SchedulingKey extends Ordered[SchedulingKey] {
  protected val EARLIEST = new Timestamp(0)
  protected val LATEST = new Timestamp(Long.MaxValue)

  protected def getTimestamp: Timestamp

  def compare(that: SchedulingKey) = {
    val r = getTimestamp.compareTo(that.getTimestamp)
    if (r == 0)
      hashCode().compareTo(that.hashCode())
    else
      r
  }
}

case class SubmitObject(id: Int, contestId: Int, teamId: Int, problemId: String, moduleType: String,
                        arrived: Timestamp, source: Array[Byte], schoolMode: Boolean, computer: Long)
  extends SchedulingKey with HasId with SubmitWithModule {
  protected val getTimestamp = arrived
  override def toString =
    "Submit(%d, C:%d, T: %d, P: %s, M: %s, A: %s, F: %s)".format(id, contestId, teamId, problemId, moduleType, arrived, schoolMode)
}

class RequestStore {
  val waiting = new mutable.HashMap[String, mutable.Set[(SchedulingKey, Promise[InvokerInstance])]]()

  def get(caps: Iterable[String]) =
    waiting.synchronized {
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
    waiting.synchronized {
      waiting.getOrElseUpdate(caps, new mutable.HashSet[(SchedulingKey, Promise[InvokerInstance])]()).add((key, p))
    }
    p
  }
}

class InvokerStore {
  val freelist = mutable.Set[InvokerInstance]()
  val badList = mutable.Set[InvokerInstance]()

  def get(m: String) =
    freelist.find(_.caps(m)).map { i =>
      freelist.remove(i)
      i
    }

  def put(i: InvokerInstance) =
    freelist.add(i)

  def bad(i: InvokerInstance) =
    badList.add(i)

  def remove(i: InvokerInstance) = {
    freelist.remove(i)
    badList.remove(i)
  }
}