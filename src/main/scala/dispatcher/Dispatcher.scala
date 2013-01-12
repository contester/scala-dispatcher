package org.stingray.contester.dispatcher

import java.sql.{Timestamp, ResultSet}
import org.stingray.contester.db.{HasId, SelectDispatcher}
import org.stingray.contester.common.SubmitWithModule
import org.stingray.contester.invokers.TimeKey

trait Submit extends TimeKey with HasId with SubmitWithModule {
  def schoolMode: Boolean = false
}

case class SubmitObject(id: Int, contestId: Int, teamId: Int, problemId: String, moduleType: String,
                        arrived: Timestamp, source: Array[Byte], override val schoolMode: Boolean, computer: Long)
  extends Submit {
  val timestamp = arrived
  override def toString =
    "Submit(%d, %s)".format(id, problemId)
}


class SubmitDispatcher(parent: DbDispatcher) extends SelectDispatcher[SubmitObject](parent.dbclient) {
  // startup: scan all started
  // rescans: scall all NULLs, start them
  // rejudge: if not in progress, start.

  val selectAllActiveQuery =
    """
      |select
      |NewSubmits.ID, NewSubmits.Contest, NewSubmits.Team, NewSubmits.Problem,
      |Languages.Ext, NewSubmits.Arrived, NewSubmits.Source, Contests.SchoolMode, NewSubmits.Computer,
      |Contests.PolygonID
      |from NewSubmits, Languages, Contests
      |where NewSubmits.Contest = Languages.Contest and NewSubmits.SrcLang = Languages.ID
      |and Contests.ID = NewSubmits.Contest
      |and Contests.PolygonID != 0 and Processed = 1
    """.stripMargin

  val doneQuery = """
  update NewSubmits set Processed = 255 where ID = ?"""

  val failedQuery = """
  update NewSubmits set Processed = 254 where ID = ?"""

  val grabAllQuery = """update NewSubmits set Processed = 1 where Processed is null"""

  val grabOneQuery = """update NewSubmits set Processed = 1 where ID = ?"""

  def rowToSubmit(row: ResultSet) =
    SubmitObject(
      row.getInt("ID"),
      row.getInt("Contest"),
      row.getInt("Team"),
      row.getString("Problem"),
      row.getString("Ext"),
      row.getTimestamp("Arrived"),
      row.getBytes("Source"),
      row.getInt("SchoolMode") == 1,
      row.getLong("Computer")
    )


  // main test entry point
  def run(m: SubmitObject) = {
    parent.getProblem(m.contestId, m.problemId).flatMap { problem =>
      Solution.test(parent.invoker, m, problem, parent.getReporter(m))
    }
  }
}

