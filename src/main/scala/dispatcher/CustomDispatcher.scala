package org.stingray.contester.dispatcher

import java.sql.{ResultSet, Timestamp}
import org.stingray.contester.invokers.TimeKey
import org.stingray.contester.common._
import com.twitter.util.Future
import org.stingray.contester.db.{HasId, SelectDispatcher, ConnectionPool}
import org.stingray.contester.testing.SolutionTester
import org.stingray.contester.testing.CustomTestingResult

case class CustomTestObject(id: Int, arrived: Timestamp, sourceModule: Module, input: Array[Byte]) extends TimeKey with HasId with SubmitWithModule {
  val timestamp = arrived
}

class CustomTestDispatcher(db: ConnectionPool, invoker: SolutionTester, store: GridfsObjectStore, storeId: String) extends SelectDispatcher[CustomTestObject](db) {
  def rowToSubmit(row: ResultSet) =
    CustomTestObject(
      row.getInt("ID"),
      row.getTimestamp("Arrived"),
      new ByteBufferModule(row.getString("Ext"), row.getBytes("Source")),
      row.getBytes("Input")
    )

  val selectAllNewQuery: String =
    """
      |select ID, Ext, Arrived, Source, Input
      |from Eval
      |where Processed is null
    """.stripMargin

  val selectAllActiveQuery =
    """
      |select ID, Ext, Arrived, Source, Input
      |from Eval
      |where Processed = 1
    """.stripMargin

  def grabOneQuery =
    """
      |update Eval set Processed = 1 where ID = ?
    """.stripMargin

  def doneQuery =
    """
      |update Eval set Processed = 255 where ID = ?
    """.stripMargin

  def failedQuery =
    """
      |update Eval set Processed = 254 where ID = ?
    """.stripMargin

  def recordResult(item: CustomTestObject, result: CustomTestingResult): Future[Unit] =
    if (result.test.isDefined)
      db.execute("update Eval set Output = ?, Timex = ?, Memory = ?, Info = ?, Result = ? where ID = ?",
        result.test.get.output.map(Blobs.getBinary(_)).getOrElse("".getBytes),
        result.test.get.run.time / 1000,
        result.test.get.run.memory,
        result.test.get.run.returnCode,
        result.test.get.run.status,
        item.id
      ).unit
    else Future.Done

  def run(item: CustomTestObject) =
    invoker.custom(item, item.sourceModule, item.input, store, new GridfsPath(storeId + "/eval"), item.id)
      .flatMap(recordResult(item, _))
}
