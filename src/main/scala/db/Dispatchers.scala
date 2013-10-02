package org.stingray.contester.db

import collection.mutable
import com.twitter.conversions.time._
import com.twitter.util.Future
import grizzled.slf4j.Logging
import java.sql.ResultSet
import org.stingray.contester.utils.Utils
import com.twitter.concurrent.AsyncMutex

trait HasId {
  def id: Int
}

// Dispatcher:
//   - can schedule submits
//   - can cancel scheduled submits
//   - subscribed to the progress

abstract class SelectDispatcher[SubmitType <: HasId](db: ConnectionPool) extends Logging {
  private val dbMutex = new AsyncMutex()

  def rowToSubmit(row: ResultSet): SubmitType

  def selectAllActiveQuery: String
  def selectAllNewQuery: String

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

  private def finishWith(id: Int) =
    activeItems.synchronized {
      activeItems.remove(id)
    }

  private def mark(query: String, id: Int) =
    dbMutex.acquire().flatMap { permit =>
      db.execute(query, id).ensure { finishWith(id); permit.release() }
    }

  private def markDone(id: Int) =
    mark(doneQuery, id)

  private def markFailed(id: Int) =
    mark(failedQuery, id)

  private def add(item: SubmitType, grab: Boolean): Future[Unit] =
  {
    if (grab)
      db.execute(grabOneQuery, item.id).unit
    else
      Future.Done
  }.map { _ =>
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

  private def getQ(query: String, grab: Boolean) =
    dbMutex.acquire().flatMap { permit =>
      select(query).flatMap { items =>
        Future.collect(items.sortBy(_.id).map(add(_, true)))
      }.ensure(permit.release())
    }

  private def rescan: Future[Unit] =
    getQ(selectAllNewQuery, true).rescue {
      case e: Throwable =>
        error("Rescan", e)
        Future.Done
    }.flatMap(_ => Utils.later(5.seconds).flatMap(_ => rescan))

  def start: Future[Unit] =
    getQ(selectAllActiveQuery, false).rescue {
      case e: Throwable =>
      error("Initial scan", e)
      Utils.later(5.seconds).flatMap(_ => start)
    }.flatMap(_ => rescan)
}