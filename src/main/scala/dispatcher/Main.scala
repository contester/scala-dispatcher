package org.stingray.contester.dispatcher

import com.mongodb.casbah.MongoConnection
import grizzled.slf4j.Logging
import java.io.File
import java.net.{URL, InetSocketAddress}
import java.util.concurrent.Executors
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.logging.{Slf4JLoggerFactory, InternalLoggerFactory}
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.rpc4.ServerPipelineFactory
import org.streum.configrity.Configuration
import org.stingray.contester.testing.SolutionTester
import org.stingray.contester.engine.InvokerSimpleApi
import org.stingray.contester.common.GridfsObjectStore
import com.mongodb.casbah.gridfs.GridFS
import org.stingray.contester.polygon._
import org.stingray.contester.problems.CommonProblemDb

object Main extends App with Logging {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  def createDbConfig(conf: Configuration) =
    new DbConfig(conf)

  val config = Configuration.load("dispatcher.conf")
  val mHost = config[String]("pdb.mhost")
  //val amqclient = AMQ.createConnection(config.detach("messaging"))

  val httpStatus = HttpStatus.bind(config[Int]("dispatcher.port"))

  val mongoDb = MongoConnection(mHost).getDB("contester")
  val pdb = new PolygonCache(mongoDb)
  val sdb = new CommonProblemDb(mongoDb)
  //Await.result(pdb.buildIndexes)
  val objectStore = new GridfsObjectStore(GridFS(mongoDb))
  val invoker = new InvokerRegistry(mHost)
  StatusPageBuilder.data("invoker") = invoker
  val tester = new SolutionTester(new InvokerSimpleApi(invoker))

  val sf = new NioServerSocketChannelFactory(
    Executors.newCachedThreadPool(),
    Executors.newCachedThreadPool())
  val bs = new ServerBootstrap(sf)
  bs.setPipelineFactory(new ServerPipelineFactory(invoker))
  bs.bind(new InetSocketAddress(9981))

  val dispatchers =
    config.get[List[String]]("dispatcher.standard").map { names =>

      val authFilter = new AuthPolygonFilter
      config.detachAll.foreach {
        case (shortName, polygonConf) =>
        authFilter.addPolygon(new PolygonBase(shortName, new URL(polygonConf[String]("url")), polygonConf[String]("username"), polygonConf[String]("password")))
      }

      val client = authFilter andThen BasicPolygonFilter andThen CachedConnectionHttpService
      val problems = new ProblemData(client, pdb, sdb, invoker)
      val result = new DbDispatchers(problems, new File(config[String]("reporting.base")), tester, objectStore)

      names.foreach { name =>
        if (config.contains(name + ".db")) {
          result.add(createDbConfig(config.detach(name)))
	}
      }
      result
    }

  val moodles =
    config.get[List[String]]("dispatcher.moodles").map { names =>
      names.filter(x => config.contains(x + ".db")).map { name =>
        new MoodleDispatcher(createDbConfig(config.detach(name)).createConnectionPool, sdb, tester, objectStore)
      }.foreach(_.scan)
    }
}
