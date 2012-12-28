package org.stingray.contester.dispatcher

import collection.mutable
import com.codahale.jerkson.Json
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.Channel
import com.twitter.util.Future
import grizzled.slf4j.Logging
import java.io.File
import org.stingray.contester.db.ConnectionPool
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems.Problem

class DbDispatcher(val dbclient: ConnectionPool, val pdata: ProblemData, val basePath: File, val invoker: InvokerRegistry, val amqconn: Channel, val amqid: String) extends Logging {
  val pscanner = new ContestTableScanner(pdata, dbclient)
  val dispatcher = new SubmitDispatcher(this)
  val evaldispatcher = new CustomTestDispatcher(dbclient, invoker)

  def f2o[A](x: Option[Future[A]]): Future[Option[A]] =
    Future.collect(x.toSeq).map(_.headOption)

  def getProblem(cid: Int, problem: String): Future[Problem] =
    pscanner.getContestPid(cid).flatMap(pdata.getProblemInfo(pscanner, _, problem))

  def amqPost(id: Int) = {
    info("Finished: %s/%d".format(amqid, id))
    amqconn.basicPublish("", "finished",
      new BasicProperties.Builder().deliveryMode(2)
        .contentType("application/json").build(),
      Json.generate(Map("db" -> amqid, "submit" -> id)).getBytes("UTF-8")
      )
  }

  def getReporter(submit: SubmitObject) =
    new CombinedResultReporter(dbclient, submit, basePath, amqid, amqPost(_))

  def start =
    pscanner.rescan.join(dispatcher.scan).join(evaldispatcher.scan).unit
}

case class DbConfig(host: String, db: String, username: String, password: String) {
  def createConnectionPool =
    new ConnectionPool(host, db, username, password)

  override def toString =
    "DbConfig(\"%s\", \"%s\", \"%s\", \"%s\")".format(host, db, username, "hunter2")
}

class DbDispatchers(val pdata: ProblemData, val basePath: File, val invoker: InvokerRegistry, val amqconn: Channel) extends Logging {
  val dispatchers = new mutable.HashMap[DbConfig, DbDispatcher]()
  val scanners = new mutable.HashMap[DbDispatcher, Future[Unit]]()

  def add(conf: DbConfig) = {
    info(conf)
    val d = new DbDispatcher(conf.createConnectionPool, pdata, new File(basePath, conf.db), invoker, amqconn, conf.db)
    scanners(d) = d.start
  }

  def remove(conf: DbConfig) = {
    dispatchers.remove(conf).foreach { d =>
      scanners.remove(d).foreach(_.cancel())
      pdata.remove(d.pscanner)
    }
  }
}