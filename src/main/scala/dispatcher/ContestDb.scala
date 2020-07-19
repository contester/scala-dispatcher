package org.stingray.contester.dispatcher

import akka.actor.{ActorRef, ActorSystem, Props}
import com.spingo.op_rabbit._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import org.stingray.contester.common.TestingStore
import org.stingray.contester.polygon.PolygonProblemClient
import org.stingray.contester.testing.{CustomTester, SolutionTester}
import play.api.Logging
import play.api.libs.json.Json
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext.Implicits.global

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
                   custom: CustomTester,
                   store: TestingStore,
                   rabbitMq: ActorRef,
                   reportbase: String,
                   fsClient:Service[Request, Response], fsBaseUrl: String) extends Logging {
  private val dispatcher = new SubmitDispatcher(db, pdb, invoker, store, rabbitMq, reportbase, fsClient, fsBaseUrl)
  private val evaldispatcher = new CustomTestDispatcher(db, custom, store, rabbitMq)

  implicit val actorSystem = ActorSystem("such-system")
  private val pscanner = actorSystem.actorOf(Props(classOf[ContestTableScanner], db, pdb))

  import com.spingo.op_rabbit.PlayJsonSupport._

  implicit private val recoveryStrategy = RecoveryStrategy.none

  val evalSub = Subscription.run(rabbitMq) {
    import Directives._
    channel(qos = 10) {
      consume(queue("contester.evalrequests")) {
        body(as[ServerSideEvalID]) { evalreq =>
          logger.info(s"Received eval $evalreq")
          ack(evaldispatcher.runthis(evalreq))
        }
      }
    }
  }

  val submitSub = Subscription.run(rabbitMq) {
    import Directives._
    channel(qos = 10000) {
      consume(queue("contester.submitrequests")) {
        body(as[SubmitMessage]) { submitreq =>
          logger.info(s"Received submit $submitreq")
          ack(dispatcher.runq(submitreq.id))
        }
      }
    }
  }
}