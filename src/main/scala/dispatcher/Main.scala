package org.stingray.contester.dispatcher

import java.net.InetSocketAddress
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import controllers.Assets
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.logging.{InternalLoggerFactory, Slf4JLoggerFactory}
import org.stingray.contester.common.MemcachedObjectCache
import org.stingray.contester.engine.InvokerSimpleApi
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.problems.SimpleProblemDb
import org.stingray.contester.proto.StatRequest
import org.stingray.contester.rpc4.ServerPipelineFactory
import org.stingray.contester.testing.SolutionTester
import org.stingray.contester.utils.ProtobufTools
import play.api.Logger

object DispatcherServer extends App {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  private val config = ConfigFactory.load()

  private val objectCache = new MemcachedObjectCache(config.getString("cache.host"))

  private val invoker = new InvokerRegistry("contester")

  val invokerApi = new InvokerSimpleApi(invoker, objectCache)
  val tester = new SolutionTester(invokerApi)

  val simpleDb =
    if (config.hasPath("simpledb")) {
      Some(SimpleProblemDb(config.getString("simpledb")))
    } else None

  private def bindInvokerTo(socket: InetSocketAddress) = {
    val sf = new NioServerSocketChannelFactory(
      Executors.newCachedThreadPool(),
      Executors.newCachedThreadPool())
    val bs = new ServerBootstrap(sf)
    bs.setPipelineFactory(new ServerPipelineFactory(invoker))
    bs.bind(socket)
  }

  implicit val actorSystem = ActorSystem("such-system")

  Logger.info("Initializing dispatchers")

  import slick.driver.MySQLDriver.api._

  import scala.collection.JavaConversions._
/*
  val dispatchers = if (config.hasPath("dispatcher.standard")) {
    val polygonBase = config.getConfig("polygons")
    val polygonNames = polygonBase.root().keys
    val contestResolver = new ContestResolver(polygonNames.map(n => n -> new URL(polygonBase.getConfig(n).getString("url"))).toMap)

    val authFilter = new AuthPolygonFilter
    polygonNames.foreach { shortName =>
      val polygonConf = polygonBase.getConfig(shortName)
      authFilter.addPolygon(new PolygonBase(shortName, new URL(polygonConf.getString("url")),
        polygonConf.getString("username"), polygonConf.getString("password")))
    }

    val client = authFilter andThen BasicPolygonFilter andThen CachedConnectionHttpService
    val problems = new ProblemData(client, polygonCache, problemDb, invokerApi)

    val rabbitMq = actorSystem.actorOf(Props[RabbitControl]())


    val result = new DbDispatchers(problems, new File(config.getString("reporting.base")),
      tester, mongoDb.objectStore, contestResolver(_))

    config.getStringList("dispatcher.standard").foreach { name =>
      if (config.hasPath(name + ".dbnext")) {
        val rabbitMq = actorSystem.actorOf(
          Props(classOf[RabbitControl], ConnectionParams.fromConfig(config.getConfig(s"${name}.op-rabbit"))),
          s"rabbit.${name}")

        result.add(config.getString(s"${name}.short"), Database.forConfig(s"${name}.dbnext"), rabbitMq)
      }
    }
    Some(result)
  } else None
*/
  val moodles = 
  if (config.hasPath("dispatcher.moodles")) {
    for (name <- config.getStringList("dispatcher.moodles"); if config.hasPath(name + ".dbnext")) yield {
        val db = Database.forConfig(s"${name}.dbnext")
        val dispatcher = new MoodleDispatcher(db, simpleDb.get, tester, simpleDb.map(_.baseUrl))
        Logger.info(s"starting actor for ${name}")
        actorSystem.actorOf(MoodleTableScanner.props(db, dispatcher))
    }
  } else Nil

  Logger.info("Starting serving")

  import play.api.mvc._
  import play.api.routing.sird._
  import play.core.server._

  val server = NettyServer.fromRouter(ServerConfig(
  port = Some(config.getInt("dispatcher.port")))) {
    case GET(p"/assets/$file*") => Assets.versioned(path = "/public", file)
    case GET(p"/invokers") => Action {
      Results.Ok(html.invokers(invoker))
    }
  }
  bindInvokerTo(new InetSocketAddress(config.getInt("dispatcher.invokerPort")))
}
