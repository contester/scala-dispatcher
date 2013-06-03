package org.stingray.contester.invokers

import java.sql.Timestamp

private object TimeKey {
  val EARLIEST = new Timestamp(0)
  val LATEST = new Timestamp(Long.MaxValue)
}

/** If type is used for scheduling inside invoker registry, it needs to descend from this trait.
  *
  */
trait SchedulingKey extends Ordered[SchedulingKey]

/** Simplest and the only used now implementation of SchedulingKey.
  *
  */
trait TimeKey extends SchedulingKey {
  /** Override this to return timestamp of the key.
    *
    * @return Timestamp of the key.
    */
  def timestamp: Timestamp

  /** Compares this against other scheduling key. If it's a timestamp, compare timestamps.
    *
    * @param that Other scheduling key.
    * @return What compare usually returns.
    */
  def compare(that: SchedulingKey): Int =
    that match {
      case x: TimeKey =>
        timestamp.compareTo(x.timestamp)
    }
}

/** TimeKey with timestamp set to the earliest possible value.
  *
  */
trait EarliestTimeKey extends TimeKey {
  val timestamp = TimeKey.EARLIEST
}
