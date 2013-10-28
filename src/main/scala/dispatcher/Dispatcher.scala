package org.stingray.contester.dispatcher

import java.sql.{Timestamp, ResultSet}
import org.stingray.contester.db.{HasId, SelectDispatcher}
import org.stingray.contester.common._
import org.stingray.contester.invokers.TimeKey
import com.twitter.util.Future
import org.stingray.contester.polygon.PolygonURL
import org.stingray.contester.testing._
import java.net.URL

trait Submit extends TimeKey with HasId with SubmitWithModule {
  def schoolMode: Boolean = false
}

case class SubmitObject(id: Int, contestId: Int, teamId: Int, problemId: String,
                        arrived: Timestamp, sourceModule: Module, override val schoolMode: Boolean, computer: Long)
  extends Submit {
  val timestamp = arrived
  override def toString =
    "Submit(%d, %d, %s, %s)".format(id, contestId, problemId, arrived)
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
      |and Contests.PolygonID != '' and Processed = 1
    """.stripMargin

  val selectAllNewQuery =
    """
      |select
      |NewSubmits.ID, NewSubmits.Contest, NewSubmits.Team, NewSubmits.Problem,
      |Languages.Ext, NewSubmits.Arrived, NewSubmits.Source, Contests.SchoolMode, NewSubmits.Computer,
      |Contests.PolygonID
      |from NewSubmits, Languages, Contests
      |where NewSubmits.Contest = Languages.Contest and NewSubmits.SrcLang = Languages.ID
      |and Contests.ID = NewSubmits.Contest
      |and Contests.PolygonID != '' and Processed is null
    """.stripMargin

  val doneQuery = """
  update NewSubmits set Processed = 255 where ID = ?"""

  val failedQuery = """
  update NewSubmits set Processed = 254 where ID = ?"""

  val grabOneQuery = """update NewSubmits set Processed = 1 where ID = ?"""

  def rowToSubmit(row: ResultSet) =
    SubmitObject(
      row.getInt("ID"),
      row.getInt("Contest"),
      row.getInt("Team"),
      row.getString("Problem"),
      row.getTimestamp("Arrived"),
      new ByteBufferModule(row.getString("Ext"), row.getBytes("Source")),
      row.getInt("SchoolMode") == 1,
      row.getLong("Computer")
    )

  def getTestingInfo(reporter: DBReporter, m: SubmitObject) =
    reporter.getAnyTestingAndState(m.id).flatMap(_.map(Future.value).getOrElse {
      parent.getPolygonProblem(m.contestId, m.problemId).flatMap { polygonProblem =>
        reporter.allocateTesting(m.id, polygonProblem.handle.uri.toString).flatMap { testingId =>
          new RawLogResultReporter(parent.basePath, m).start.map { _ =>
            new TestingInfo(testingId, polygonProblem.handle.uri.toString, Seq())
          }
        }
      }
    })

  // main test entry point
  def run(m: SubmitObject) = {
    val reporter = new DBReporter(parent.dbclient)
    getTestingInfo(reporter, m).flatMap { testingInfo =>
      reporter.registerTestingOnly(m, testingInfo.testingId).flatMap { _ =>
        val combinedProgress = new CombinedSingleProgress(new DBSingleResultReporter(parent.dbclient, m, testingInfo.testingId), new RawLogResultReporter(parent.basePath, m))
        parent.pdata.getPolygonProblem(PolygonURL(new URL(testingInfo.problemId)))
            .flatMap(parent.pdata.sanitizeProblem).flatMap { problem =>
          parent.invoker(m, m.sourceModule, problem, combinedProgress, m.schoolMode, parent.store, new InstanceSubmitTestingHandle(parent.storeId, m.id, testingInfo.testingId),
            testingInfo.state.toMap.mapValues(new RestoredResult(_))).flatMap { sr =>
            combinedProgress.db.finish(sr, m.id, testingInfo.testingId).join(combinedProgress.raw.finish(sr)).unit
          }
        }
      }
    }
  }
}

