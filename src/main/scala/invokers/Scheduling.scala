package org.stingray.contester.invokers

import java.sql.Timestamp

object SchedulingKey {
  val EARLIEST = new Timestamp(0)
  val LATEST = new Timestamp(Long.MaxValue)
}

trait SchedulingKey extends Ordered[SchedulingKey]

trait TimeKey extends SchedulingKey {
  def timestamp: Timestamp

  def compare(that: SchedulingKey): Int =
    that match {
      case x: TimeKey =>
        timestamp.compareTo(x.timestamp)
    }
}

trait EarliestTimeKey extends TimeKey {
  val timestamp = SchedulingKey.EARLIEST
}
