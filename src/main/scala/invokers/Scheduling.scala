package org.stingray.contester.invokers

import java.sql.Timestamp

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
