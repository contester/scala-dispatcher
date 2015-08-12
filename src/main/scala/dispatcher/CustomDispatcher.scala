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

class CustomTestDispatcher(db: ConnectionPool, invoker: SolutionTester, storeId: String) {
  def recordResult(item: CustomTestObject, result: CustomTestingResult): Future[Unit] =
    if (result.test.isDefined)
      db.execute("update Eval set Output = ?, Timex = ?, Memory = ?, Info = ?, Result = ?, Processed = 255 where ID = ?",
        result.test.get.output.map(Blobs.getBinary(_)).getOrElse("".getBytes),
        result.test.get.run.time / 1000,
        result.test.get.run.memory,
        result.test.get.run.returnCode,
        result.test.get.run.status.getNumber,
        item.id
      ).unit
    else Future.Done

  def run(item: CustomTestObject) =
    invoker.custom(item, item.sourceModule, item.input, new GridfsPath(storeId + "/eval"), item.id)
      .flatMap(recordResult(item, _))
}
