package org.stingray.contester.dispatcher

import akka.actor.ActorRef
import com.github.nscala_time.time.Imports._
import com.spingo.op_rabbit.Message
import org.stingray.contester.common._
import org.stingray.contester.dbmodel.TimeMs
import org.stingray.contester.invokers.TimeKey
import org.stingray.contester.testing.{CustomTester, CustomTestingResult}
import play.api.libs.json.Json
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class CustomTestObject(id: Long, contest: Int, team: Int, arrived: DateTime, sourceModule: Module,
                            input: Array[Byte]) extends TimeKey with SubmitWithModule {
  val timestamp = arrived
}

case class CustomTestResult(id: Long, contest:Int, team: Int)

object CustomTestResult {
  implicit val formatCustomTestResult = Json.format[CustomTestResult]
}

class CustomTestDispatcher(db: JdbcBackend#DatabaseDef, invoker: CustomTester, store: TestingStore, rabbitMq: ActorRef) {
  import CPModel._
  import com.spingo.op_rabbit.PlayJsonSupport._
  import org.stingray.contester.utils.Fu._
  import slick.jdbc.PostgresProfile.api._

  private[this] def updateCustom(id: Long, output: Array[Byte], timeMs: Long, memoryBytes: Long, returnCode: Long, resultCode: Int) = {
    import org.stingray.contester.utils.Dbutil._
    sqlu"""update custom_test set output = $output,
                time_ms = $timeMs,
                memory_bytes = $memoryBytes,
                return_code = $returnCode,
                result_code = $resultCode,
                finish_time = CURRENT_TIMESTAMP where ID = $id"""
  }

  private[this] def recordResult(item: CustomTestObject, result: CustomTestingResult) = {
    db.run(
      result.test.map { tr =>
        updateCustom(item.id, tr.output.map(Blobs.getBinary).getOrElse("".getBytes), tr.run.time / 1000, tr.run.memory,
          tr.run.returnCode, tr.run.status.value)
      }.getOrElse {
        val tr = result.compilation
        updateCustom(item.id, tr.stdOut, tr.time / 1000, tr.memory, 0, tr.status.value)
      }
    ).map { x =>
        rabbitMq ! Message.queue(CustomTestResult(item.id, item.contest, item.team), queue = "contester.evals")
      ()
      }
  }

  def runthis(what: ServerSideEvalID): Future[Unit] = {
    db.run(customTestByID(what.id).result.headOption).flatMap { optItem =>
      optItem.map { x =>
        val c = CustomTestObject(x._1, x._2, x._3, x._4, new ByteBufferModule(x._5, x._6), x._7)
        run(c)
      }.getOrElse(Done)
    }
  }


  private[this] def run(item: CustomTestObject): Future[Unit] =
    invoker(item, item.sourceModule, item.input, store.custom(item.id))
      .flatMap(x => recordResult(item, x))
}
