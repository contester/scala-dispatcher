package org.stingray.contester.dispatcher

import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import java.io.File
import org.stingray.contester.db.ConnectionPool
import org.stingray.contester.problems.Problem
import org.stingray.contester.testing.{CombinedResultReporter, SolutionTester}
import org.stingray.contester.common.GridfsObjectStore
import com.mongodb.casbah.MongoDB

class DbDispatcher(val dbclient: ConnectionPool, val pdata: ProblemData, val basePath: File, val invoker: SolutionTester,
                   val store: GridfsObjectStore, val storeId: String, val mongoDb: MongoDB) extends Logging {
  val pscanner = new ContestTableScanner(pdata, dbclient)
  val dispatcher = new SubmitDispatcher(this)
  val evaldispatcher = new CustomTestDispatcher(dbclient, invoker, store, storeId)

  def f2o[A](x: Option[Future[A]]): Future[Option[A]] =
    Future.collect(x.toSeq).map(_.headOption)

  def getProblem(cid: Int, problem: String): Future[Problem] =
    pscanner(cid).flatMap(pdata.getProblemInfo(pscanner, _, problem))

  def getReporter(submit: SubmitObject) =
    CombinedResultReporter(submit, dbclient, basePath)

  def start =
    pscanner.rescan.join(dispatcher.scan).join(evaldispatcher.scan).unit
}

case class DbConfig(host: String, db: String, username: String, password: String) {
  def createConnectionPool =
    new ConnectionPool(host, db, username, password)

  override def toString =
    "DbConfig(\"%s\", \"%s\", \"%s\", \"%s\")".format(host, db, username, "hunter2")
}

class DbDispatchers(val pdata: ProblemData, val basePath: File, val invoker: SolutionTester, val store: GridfsObjectStore, val mongoDb: MongoDB) extends Logging {
  val dispatchers = new mutable.HashMap[DbConfig, DbDispatcher]()
  val scanners = new mutable.HashMap[DbDispatcher, Future[Unit]]()

  def add(conf: DbConfig) = {
    info(conf)
    val d = new DbDispatcher(conf.createConnectionPool, pdata, new File(basePath, conf.db), invoker, store, conf.db, mongoDb)
    scanners(d) = d.start
  }

  def remove(conf: DbConfig) = {
    dispatchers.remove(conf).foreach { d =>
      scanners.remove(d).foreach(_.cancel())
      pdata.remove(d.pscanner)
    }
  }
}