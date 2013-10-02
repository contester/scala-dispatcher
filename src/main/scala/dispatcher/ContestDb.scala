package org.stingray.contester.dispatcher

import collection.mutable
import com.twitter.util.Future
import grizzled.slf4j.Logging
import java.io.File
import org.stingray.contester.db.ConnectionPool
import org.stingray.contester.testing.SolutionTester
import org.stingray.contester.common.GridfsObjectStore
import java.net.URL
import org.streum.configrity.Configuration
import org.stingray.contester.polygon.PolygonProblem

class DbDispatcher(val dbclient: ConnectionPool, val pdata: ProblemData, val basePath: File, val invoker: SolutionTester,
                   val store: GridfsObjectStore, val storeId: String, polygonBase: URL) extends Logging {
  val pscanner = new ContestTableScanner(pdata, dbclient, polygonBase)
  val dispatcher = new SubmitDispatcher(this)
  val evaldispatcher = new CustomTestDispatcher(dbclient, invoker, store, storeId)

  def f2o[A](x: Option[Future[A]]): Future[Option[A]] =
    Future.collect(x.toSeq).map(_.headOption)

  def getPolygonProblem(cid: Int, problem: String) =
    pscanner(cid).flatMap(pdata.getPolygonProblem(_, problem))

  def sanitizeProblem(problem: PolygonProblem) =
    pdata.sanitizeProblem(problem)

  def start =
    pscanner.rescan.join(dispatcher.start).join(evaldispatcher.start).unit
}

class DbConfig(conf: Configuration) {
  val short = conf.get[String]("short").getOrElse(conf[String]("db"))
  val polygonBase = new URL(conf[String]("polygon"))

  def createConnectionPool =
    new ConnectionPool(conf[String]("host"),
      conf[String]("db"),
      conf[String]("username"),
      conf[String]("password"))
}

class DbDispatchers(val pdata: ProblemData, val basePath: File, val invoker: SolutionTester, val store: GridfsObjectStore) extends Logging {
  val dispatchers = new mutable.HashMap[DbConfig, DbDispatcher]()
  val scanners = new mutable.HashMap[DbDispatcher, Future[Unit]]()

  def add(conf: DbConfig) = {
    info(conf)
    val d = new DbDispatcher(conf.createConnectionPool, pdata, new File(basePath, conf.short), invoker, store, conf.short, conf.polygonBase)
    scanners(d) = d.start
  }
}