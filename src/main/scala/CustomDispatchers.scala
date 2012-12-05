package org.stingray.contester

import com.twitter.util.Future
import java.sql.{Timestamp, ResultSet}
import org.stingray.contester.common.{SubmitWithModule, Blobs}
import org.stingray.contester.db.{ConnectionPool, SelectDispatcher}
import org.stingray.contester.invokers.InvokerRegistry

case class CustomTestObject(id: Int, moduleType: String, arrived: Timestamp, source: Array[Byte], input: Array[Byte]) extends SchedulingKey with HasId with SubmitWithModule {
  protected val getTimestamp = arrived
}

object Custom {
  def test(invoker: InvokerRegistry, submit: CustomTestObject): Future[Option[CustomTestResult]] =
    invoker.wrappedGetClear(submit.sourceModule.getType, submit, "compile")(Compiler(_, submit.sourceModule))
      .flatMap { r =>
        if (r.success) {
          invoker.wrappedGetClear(r.module.get.getType, submit, "custom")(CustomTester(_, r.module.get, submit.input)).map(Some(_))
        } else Future.None
      }
}
class CustomTestDispatcher(db: ConnectionPool, invoker: InvokerRegistry) extends SelectDispatcher[CustomTestObject](db) {
  def rowToSubmit(row: ResultSet) =
    CustomTestObject(
      row.getInt("ID"),
      row.getString("Ext"),
      row.getTimestamp("Arrived"),
      row.getBytes("Source"),
      row.getBytes("Input")
    )

  val selectAllActiveQuery =
    """
      |select ID, Ext, Arrived, Source, Input
      |from Eval
      |where Processed = 1
    """.stripMargin

  def grabAllQuery =
    """
      |update Eval set Processed = 1 where Processed is null
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

  def recordResult(item: CustomTestObject, resultOpt: Option[CustomTestResult]): Future[Unit] =
    if (resultOpt.isDefined)
      db.execute("update Eval set Output = ?, Timex = ?, Memory = ?, Info = ?, Result = ? where ID = ?",
        resultOpt.get.output.map(Blobs.getBinary(_)).getOrElse("".getBytes),
        resultOpt.get.run.time / 1000,
        resultOpt.get.run.memory,
        resultOpt.get.run.returnCode,
        resultOpt.get.run.status,
        item.id
      ).unit
    else Future.Done

  def run(item: CustomTestObject) =
    Custom.test(invoker, item).flatMap(recordResult(item, _))
}