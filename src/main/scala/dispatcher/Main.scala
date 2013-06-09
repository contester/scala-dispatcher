package org.stingray.contester.dispatcher

import com.mongodb.casbah.MongoConnection
import grizzled.slf4j.Logging
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.logging.{Slf4JLoggerFactory, InternalLoggerFactory}
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.polygon.{CommonPolygonDb, PolygonClient}
import org.stingray.contester.rpc4.ServerPipelineFactory
import org.streum.configrity.Configuration
import org.stingray.contester.testing.SolutionTester
import org.stingray.contester.engine.InvokerSimpleApi

object Main extends App with Logging {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  def createDbConfig(conf: Configuration) =
    DbConfig(conf[String]("host"),
      conf[String]("db"),
      conf[String]("username"),
      conf[String]("password"))

  val config = Configuration.load("dispatcher.conf")
  val mHost = config[String]("pdb.mhost")
  //val amqclient = AMQ.createConnection(config.detach("messaging"))

  val httpStatus = HttpStatus.bind(config[Int]("dispatcher.port"))

  val pdb = new CommonPolygonDb(MongoConnection(mHost).getDB("contester"))
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
      val client = PolygonClient(config.detach("polygon"))
      val problems = new ProblemData(client, pdb, invoker)
      val result = new DbDispatchers(problems, new File(config[String]("reporting.base")), tester)

      names.foreach { name =>
        if (config.contains(name))
          result.add(createDbConfig(config.detach(name)))
      }
      result
    }

  val moodles =
    config.get[List[String]]("dispatcher.moodles").map { names =>
      names.filter(x => config.contains(x + ".db")).map { name =>
        new MoodleDispatcher(createDbConfig(config.detach(name)).createConnectionPool, pdb, tester)
      }.foreach(_.scan)
    }
}
