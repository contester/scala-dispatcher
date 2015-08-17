package org.stingray.contester.dispatcher

import java.sql.Timestamp

import akka.actor.ActorRef
import com.spingo.op_rabbit.QueueMessage
import org.stingray.contester.common._
import org.stingray.contester.invokers.TimeKey
import org.stingray.contester.testing.{CustomTestingResult, SolutionTester}
import play.api.libs.json.Json
import slick.jdbc.{GetResult, JdbcBackend}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class CustomTestObject(id: Int, contest: Int, team: Int, arrived: Timestamp, sourceModule: Module,
                            input: Array[Byte]) extends TimeKey with SubmitWithModule {
  val timestamp = arrived
}
import slick.driver.MySQLDriver.api._
import org.stingray.contester.utils.Dbutil._

object CustomTestObject {
  implicit val getResult = GetResult(r =>
    CustomTestObject(r.nextInt(), r.nextInt(), r.nextInt(), r.nextTimestamp(),
      new ByteBufferModule(r.nextString(), r.nextBytes()), r.nextBytes())
  )
}

case class CustomTestResult(id: Int, contest:Int, team: Int)

object CustomTestResult {
  implicit val formatCustomTestResult = Json.format[CustomTestResult]
}

class CustomTestDispatcher(db: JdbcBackend#DatabaseDef, invoker: SolutionTester, storeId: String, rabbitMq: ActorRef) {
  import org.stingray.contester.utils.Fu._
  import com.spingo.op_rabbit.PlayJsonSupport._


  def recordResult(item: CustomTestObject, result: CustomTestingResult) =
    if (result.test.isDefined)
      db.run(
        sqlu"""update Eval set Output = ${result.test.get.output.map(Blobs.getBinary(_)).getOrElse("".getBytes)},
              Timex = ${result.test.get.run.time / 1000},
              Memory = ${result.test.get.run.memory},
              Info = ${result.test.get.run.returnCode},
              Result = ${result.test.get.run.status.getNumber},
              Processed = 255 where ID = ${item.id}"""
      ).map { x =>
        rabbitMq ! QueueMessage(CustomTestResult(item.id, item.contest, item.team), queue = "contester.evals")
        ()
      }
    else Done

  def runthis(what: ServerSideEvalID): Future[Unit] = {
    db.run(sql"""select ID, Contest, Team, Arrived, Ext, Source, Input from Eval where ID = ${what.id}""".as[CustomTestObject])
      .map(_.headOption).flatMap { optItem =>
      optItem.map(run(_)).getOrElse(Done)
      }
    }


  def run(item: CustomTestObject): Future[Unit] =
    invoker.custom(item, item.sourceModule, item.input, new GridfsPath(storeId + "/eval"), item.id)
      .flatMap(x => recordResult(item, x))
}
