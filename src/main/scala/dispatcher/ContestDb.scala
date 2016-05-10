package org.stingray.contester.dispatcher

import java.sql.Timestamp

import akka.actor.{ActorRef, ActorSystem, Props}
import com.spingo.op_rabbit._
import play.api.libs.json.Json
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext.Implicits.global
import com.twitter.util.Future
import grizzled.slf4j.Logging
import java.io.File

import org.stingray.contester.common.TestingStore

import scala.concurrent.{Future => ScalaFuture}
import org.stingray.contester.testing.SolutionTester
import org.stingray.contester.polygon.{PolygonProblem, PolygonProblemClient}

case class ServerSideEvalID(id: Int)

object ServerSideEvalID {
  implicit val formatServerSideEvalMessage = Json.format[ServerSideEvalID]
}

case class SubmitMessage(id: Int)
object SubmitMessage {
  implicit val formatSubmitMessage = Json.format[SubmitMessage]
}

class DbDispatcher(db: JdbcBackend#DatabaseDef, pdb: PolygonProblemClient,
                   invoker: SolutionTester,
                   store: TestingStore,
                   rabbitMq: ActorRef) extends Logging {
  val dispatcher = new SubmitDispatcher(db, pdb, invoker, store, rabbitMq)
  val evaldispatcher = new CustomTestDispatcher(db, invoker, store, rabbitMq)

  implicit val actorSystem = ActorSystem("such-system")
  val pscanner = actorSystem.actorOf(Props(classOf[ContestTableScanner], db, pdb))

  import com.spingo.op_rabbit.PlayJsonSupport._

  val evalSub = Subscription.run(rabbitMq) {
    import Directives._
    channel(qos = 1) {
      consume(queue("contester.evalrequests")) {
        body(as[ServerSideEvalID]) { evalreq =>
          info(s"Received $evalreq")
          ack(evaldispatcher.runthis(evalreq))
        }
      }
    }
  }
  import org.stingray.contester.utils.Fu._

  val submitSub = Subscription.run(rabbitMq) {
    import Directives._
    channel(qos = 1000) {
      consume(queue("contester.submitrequests")) {
        body(as[SubmitMessage]) { submitreq =>
          info(s"Received $submitreq")
          ack(dispatcher.runq(submitreq.id))
        }
      }
    }
  }
}