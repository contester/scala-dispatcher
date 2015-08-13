package org.stingray.contester.dispatcher

import java.sql.Timestamp

import org.stingray.contester.common._
import org.stingray.contester.invokers.TimeKey
import org.stingray.contester.testing.{CustomTestingResult, SolutionTester}
import slick.jdbc.JdbcBackend
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class CustomTestObject(id: Int, arrived: Timestamp, sourceModule: Module, input: Array[Byte]) extends TimeKey with SubmitWithModule {
  val timestamp = arrived
}

class CustomTestDispatcher(db: JdbcBackend#DatabaseDef, invoker: SolutionTester, storeId: String) {
  import slick.driver.MySQLDriver.api._
  import org.stingray.contester.utils.Dbutil._

  def recordResult(item: CustomTestObject, result: CustomTestingResult) =
    if (result.test.isDefined)
      db.run(
        sqlu"""update Eval set Output = ${result.test.get.output.map(Blobs.getBinary(_)).getOrElse("".getBytes)},
              Timex = ${result.test.get.run.time / 1000},
              Memory = ${result.test.get.run.memory},
              Info = ${result.test.get.run.returnCode},
              Result = ${result.test.get.run.status.getNumber},
              Processed = 255 where ID = ${item.id}"""
      ).map(_ => ())
    else Future.successful(())

  import com.twitter.bijection.twitter_util.UtilBijections._

  def run(item: CustomTestObject) =
    invoker.custom(item, item.sourceModule, item.input, new GridfsPath(storeId + "/eval"), item.id)
      .flatMap(x => twitter2ScalaFuture[Unit].invert(recordResult(item, x)))
}
