package org.stingray.contester.dispatcher

import java.sql.Timestamp

import akka.actor.ActorRef
import com.spingo.op_rabbit.consumer.Subscription
import com.typesafe.config.Config
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json
import scala.concurrent.ExecutionContext.Implicits.global
import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import java.io.File
import org.stingray.contester.db.ConnectionPool
import org.stingray.contester.testing.SolutionTester
import org.stingray.contester.common.{ByteBufferModule, GridfsObjectStore}
import java.net.URL
import org.stingray.contester.polygon.{ContestHandle, PolygonProblem}

class ContestResolver(polygonResolver: (String) => URL) {
  def apply(source: PolygonContestId): ContestHandle =
    new ContestHandle(new URL(polygonResolver(source.polygon), "c/" + source.contestId + "/"))
}

case class ServerSideEvalMessage(id: Int, contest: Int, team: Int, ext: String, source: Array[Byte], input: Array[Byte])
object ServerSideEvalMessage {
  implicit val formatServerSideEvalMessage = Json.format[ServerSideEvalMessage]
}


class DbDispatcher(val dbclient: ConnectionPool, val pdata: ProblemData, val basePath: File, val invoker: SolutionTester,
                   val storeId: String, contestResolver: PolygonContestId => ContestHandle,
                   val rabbitMq: ActorRef) extends Logging {
  val pscanner = new ContestTableScanner(pdata, dbclient, contestResolver)
  val dispatcher = new SubmitDispatcher(this)
  val evaldispatcher = new CustomTestDispatcher(dbclient, invoker, storeId)

  import com.spingo.op_rabbit.PlayJsonSupport._

  import com.twitter.bijection.twitter_util.UtilBijections

  val subscription = new Subscription {
    // A qos of 3 will cause up to 3 concurrent messages to be processed at any given time.
    def config = channel(qos = 1) {
      consume(queue("contester.evalrequests")) {
        body(as[ServerSideEvalMessage]) { evalreq =>
          info(s"Received $evalreq")
          val acked = evaldispatcher.run(CustomTestObject(evalreq.id,
            new Timestamp((new java.util.Date()).getTime),
            new ByteBufferModule(evalreq.ext, evalreq.source), evalreq.input))
          ack(UtilBijections.twitter2ScalaFuture.apply(acked))
        }
      }
    }
  }

  rabbitMq ! subscription


  def f2o[A](x: Option[Future[A]]): Future[Option[A]] =
    Future.collect(x.toSeq).map(_.headOption)

  def getPolygonProblem(cid: Int, problem: String) =
    pscanner(cid).flatMap(pdata.getPolygonProblem(_, problem))

  def sanitizeProblem(problem: PolygonProblem) =
    pdata.sanitizeProblem(problem)

  def start =
    pscanner.rescan.join(dispatcher.start).unit
}

class DbConfig(conf: Config) {
  val short =
    if (conf.hasPath("short"))
      conf.getString("short")
    else
      conf.getString("db")

  def createConnectionPool =
    new ConnectionPool(conf.getString("host"),
      conf.getString("db"),
      conf.getString("username"),
      conf.getString("password"))
}

class DbDispatchers(val pdata: ProblemData, val basePath: File, val invoker: SolutionTester,
                    val store: GridfsObjectStore, contestResolver: PolygonContestId => ContestHandle,
                     rabbitMq: ActorRef) extends Logging {
  val dispatchers = new mutable.HashMap[DbConfig, DbDispatcher]()
  val scanners = new mutable.HashMap[DbDispatcher, Future[Unit]]()

  def add(conf: DbConfig) = {
    info(conf)
    val d = new DbDispatcher(conf.createConnectionPool, pdata, new File(basePath, conf.short), invoker, conf.short,
      contestResolver, rabbitMq)
    scanners(d) = d.start
  }
}