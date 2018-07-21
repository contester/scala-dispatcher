package org.stingray.contester.dispatcher

import java.sql.Timestamp

import akka.actor.ActorRef
import com.spingo.op_rabbit.Message
import org.stingray.contester.common._
import org.stingray.contester.invokers.TimeKey
import org.stingray.contester.testing.{CustomTester, CustomTestingResult}
import play.api.libs.json.Json
import slick.jdbc.{GetResult, JdbcBackend}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class CustomTestObject(id: Int, contest: Int, team: Int, arrived: Timestamp, sourceModule: Module,
                            input: Array[Byte]) extends TimeKey with SubmitWithModule {
  val timestamp = arrived
}

object CustomTestObject {
  import slick.jdbc.MySQLProfile.api._
  import org.stingray.contester.utils.Dbutil._

  implicit val getResult = GetResult(r =>
    CustomTestObject(r.nextInt(), r.nextInt(), r.nextInt(), r.nextTimestamp(),
      new ByteBufferModule(r.nextString(), r.nextBytes()), r.nextBytes())
  )
}

case class CustomTestResult(id: Int, contest:Int, team: Int)

object CustomTestResult {
  implicit val formatCustomTestResult = Json.format[CustomTestResult]
}

class CustomTestDispatcher(db: JdbcBackend#DatabaseDef, invoker: CustomTester, store: TestingStore, rabbitMq: ActorRef) {
  import slick.jdbc.MySQLProfile.api._
  import org.stingray.contester.utils.Dbutil._
  import org.stingray.contester.utils.Fu._
  import com.spingo.op_rabbit.PlayJsonSupport._

  def recordResult(item: CustomTestObject, result: CustomTestingResult) =
    result.test.map { tr =>
      db.run(
        sqlu"""update Eval set Output = ${tr.output.map(Blobs.getBinary).getOrElse("".getBytes)},
                Timex = ${tr.run.time / 1000},
                Memory = ${tr.run.memory},
                Info = ${tr.run.returnCode},
                Result = ${tr.run.status.value},
                Processed = 255 where ID = ${item.id}"""
      )
    }.getOrElse {
      val tr = result.compilation
      db.run(sqlu"""update Eval set Output = ${tr.stdOut},
                Timex = ${tr.time / 1000},
                Memory = ${tr.memory},
                Result = ${tr.status.value},
                Processed = 255 where ID = ${item.id}""")
    }.map { x =>
        rabbitMq ! Message.queue(CustomTestResult(item.id, item.contest, item.team), queue = "contester.evals")
      ()
      }

  def runthis(what: ServerSideEvalID): Future[Unit] = {
    db.run(sql"""select ID, Contest, Team, Arrived, Ext, Source, Input from Eval where ID = ${what.id}""".as[CustomTestObject])
      .map(_.headOption).flatMap { optItem =>
      optItem.map(run(_)).getOrElse(Done)
      }
    }


  def run(item: CustomTestObject): Future[Unit] =
    invoker(item, item.sourceModule, item.input, store.custom(item.id))
      .flatMap(x => recordResult(item, x))
}
