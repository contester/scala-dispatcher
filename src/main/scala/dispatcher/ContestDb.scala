package org.stingray.contester.dispatcher

/*
import java.sql.Timestamp

import akka.actor.{Props, ActorSystem, ActorRef}
import com.spingo.op_rabbit._
import com.typesafe.config.Config
import org.stingray.contester.invokers.TimeKey
import play.api.libs.json.Json
import slick.jdbc.JdbcBackend
import scala.concurrent.ExecutionContext.Implicits.global
import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import java.io.File
import org.stingray.contester.testing.SolutionTester
import org.stingray.contester.common.{ByteBufferModule, GridfsObjectStore}
import java.net.URL
import org.stingray.contester.polygon.{ContestHandle, PolygonProblem}

class ContestResolver(polygonResolver: (String) => URL) {
  def apply(source: PolygonContestId): ContestHandle =
    new ContestHandle(new URL(polygonResolver(source.polygon), "c/" + source.contestId + "/"))
}

import com.github.nscala_time.time.Imports.DateTime

case class ServerSideEvalID(id: Int)

object ServerSideEvalID {
  implicit val formatServerSideEvalMessage = Json.format[ServerSideEvalID]
}

case class SubmitMessage(id: Int)
object SubmitMessage {
  implicit val formatSubmitMessage = Json.format[SubmitMessage]
}

class DbDispatcher(val pdata: ProblemData, val basePath: File, val invoker: SolutionTester,
                   val storeId: String, contestResolver: PolygonContestId => ContestHandle,
                   val rabbitMq: ActorRef, dbnext: JdbcBackend#DatabaseDef) extends Logging {
  val dispatcher = new SubmitDispatcher(this, dbnext)
  val evaldispatcher = new CustomTestDispatcher(dbnext, invoker, storeId, rabbitMq)

  implicit val actorSystem = ActorSystem("such-system")
  val pscanner = actorSystem.actorOf(ContestTableScanner.props(pdata, dbnext, contestResolver))

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

  val submitSub = Subscription.run(rabbitMq) {
    import Directives._
    channel(qos = 1000) {
      consume(queue("contester.submitrequests")) {
        body(as[SubmitMessage]) { submitreq =>
          info(s"Received $submitreq")
          val acked = dispatcher.runq(submitreq.id)
          ack(acked)
        }
      }
    }
  }

  import org.stingray.contester.utils.Fu._
  def getPolygonProblem(cid: Int, problem: String): Future[PolygonProblem] = {
    import akka.pattern.ask
    import scala.concurrent.duration._

    pscanner.ask(ContestTableScanner.GetContest(cid))(15 minutes).mapTo[ContestTableScanner.GetContestResponse]
      .flatMap(x => pdata.getPolygonProblem(x.row, problem))
  }

  def sanitizeProblem(problem: PolygonProblem) =
    pdata.sanitizeProblem(problem)
}

class DbDispatchers(val pdata: ProblemData, val basePath: File, val invoker: SolutionTester,
                    val store: GridfsObjectStore, contestResolver: PolygonContestId => ContestHandle) extends Logging {
  def add(shortName: String, dbnext: JdbcBackend#DatabaseDef, rabbitMq: ActorRef) = {
    val d = new DbDispatcher(pdata, new File(basePath, shortName), invoker, shortName,
      contestResolver, rabbitMq, dbnext)
  }
}
*/