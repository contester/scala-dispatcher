package org.stingray.contester.db

import collection.mutable
import com.twitter.conversions.time._
import com.twitter.util.Future
import grizzled.slf4j.Logging
import java.sql.ResultSet
import org.stingray.contester.utils.Utils

trait HasId {
  def id: Int
}

// Dispatcher:
//   - can schedule submits
//   - can cancel scheduled submits
//   - subscribed to the progress

abstract class SelectDispatcher[SubmitType <: HasId](db: ConnectionPool) extends Logging {
  def rowToSubmit(row: ResultSet): SubmitType

  def selectAllActiveQuery: String
  def grabAllQuery: String

  def grabOneQuery: String
  def doneQuery: String
  def failedQuery: String

  def run(item: SubmitType): Future[Unit]

  private val activeItems = new mutable.HashMap[Int, Future[Unit]]()

  def select(query: String, params: Any*): Future[Seq[SubmitType]] =
    db.select(query, params)(rowToSubmit(_))
      .onFailure(error(query, _))
      .rescue {
      case _ => Utils.later(10.seconds).flatMap(_ => select(query, params))
    }

  def finishWith(id: Int) =
    activeItems.remove(id)

  def markDone(id: Int) =
    db.execute(doneQuery, id).ensure(finishWith(id))

  def markFailed(id: Int) =
    db.execute(failedQuery, id).ensure(finishWith(id))

  def add(item: SubmitType): Unit =
    activeItems.synchronized {
      if (!activeItems.contains(item.id)) {
        activeItems(item.id) =
          run(item).onFailure { e =>
            error(item, e)
            markFailed(item.id)
          } .onSuccess { _ =>
            markDone(item.id)
          }
      }
    }

  def grabAll =
    db.execute(grabAllQuery)

  // TODO: Invent non-racy way to cancel testings.

  def rescanActive =
    select(selectAllActiveQuery)
      .map(_.sortBy(_.id).map(add))

  def scan: Future[Unit] =
    grabAll.unit.flatMap { _ =>
      rescanActive.rescue {
        case e: Throwable =>
          error("Scan", e)
          Future.Done
      }.flatMap(_ => Utils.later(5.seconds).flatMap(_ => scan))
    }
}