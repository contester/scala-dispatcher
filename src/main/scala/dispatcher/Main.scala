package org.stingray.contester.dispatcher

import java.io.File
import java.net.{URL, InetSocketAddress}
import java.util.concurrent.Executors
import akka.actor.{Props, ActorSystem}
import com.spingo.op_rabbit.RabbitControl
import controllers.Assets
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.logging.{Slf4JLoggerFactory, InternalLoggerFactory}
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.rpc4.ServerPipelineFactory
import org.stingray.contester.testing.SolutionTester
import org.stingray.contester.engine.InvokerSimpleApi
import org.stingray.contester.common.{MemcachedObjectCache, MongoDBInstance}
import org.stingray.contester.polygon._
import org.stingray.contester.problems.CommonProblemDb

import com.typesafe.config.{Config, ConfigFactory}
import play.api.Logger

object DispatcherServer extends App {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  private def createDbConfig(conf: Config) =
    new DbConfig(conf)

  private val config = ConfigFactory.load()
  private val mongoDb = MongoDBInstance(config.getString("pdb.mongoUrl")).right.get

  private val objectCache = new MemcachedObjectCache(config.getString("cache.host"))

  private val polygonCache = new PolygonCache(mongoDb.db)
  private val problemDb = new CommonProblemDb(mongoDb.db, mongoDb.objectStore)
  private val invoker = new InvokerRegistry("contester", mongoDb)

  val invokerApi = new InvokerSimpleApi(invoker, objectCache)
  val tester = new SolutionTester(invokerApi)

  private def bindInvokerTo(socket: InetSocketAddress) = {
    val sf = new NioServerSocketChannelFactory(
      Executors.newCachedThreadPool(),
      Executors.newCachedThreadPool())
    val bs = new ServerBootstrap(sf)
    bs.setPipelineFactory(new ServerPipelineFactory(invoker))
    bs.bind(socket)
  }

  implicit val actorSystem = ActorSystem("such-system")
  val rabbitMq = actorSystem.actorOf(Props[RabbitControl])

  Logger.info("Initializing dispatchers")

  import scala.collection.JavaConversions._

  val dispatchers = {
    val polygonBase = config.getConfig("polygons")
    val polygonNames = polygonBase.root().keys
    println(polygonNames)
    val contestResolver = new ContestResolver(polygonNames.map(n => n -> new URL(polygonBase.getConfig(n).getString("url"))).toMap)

    val authFilter = new AuthPolygonFilter
    polygonNames.foreach { shortName =>
      val polygonConf = polygonBase.getConfig(shortName)
      authFilter.addPolygon(new PolygonBase(shortName, new URL(polygonConf.getString("url")),
        polygonConf.getString("username"), polygonConf.getString("password")))
    }

    val client = authFilter andThen BasicPolygonFilter andThen CachedConnectionHttpService
    val problems = new ProblemData(client, polygonCache, problemDb, invokerApi)
    val result = new DbDispatchers(problems, new File(config.getString("reporting.base")),
      tester, mongoDb.objectStore, contestResolver(_), rabbitMq)

    config.getStringList("dispatcher.standard").foreach { name =>
      if (config.hasPath(name + ".db")) {
        result.add(createDbConfig(config.getConfig(name)))
      }
    }
    result
  }

  val moodles =
    if (config.hasPath("dispatcher.moodles"))
      config.getStringList("dispatcher.moodles").filter(x => config.hasPath(x + ".db")).map { name =>
          new MoodleDispatcher(createDbConfig(config.getConfig(name)).createConnectionPool, problemDb, tester)
        }.foreach(_.start)
    else
      ()

  Logger.info("Starting serving")

  import play.core.server._
  import play.api.routing.sird._
  import play.api.mvc._

  val server = NettyServer.fromRouter(ServerConfig(
  port = Some(config.getInt("dispatcher.port")))) {
    case GET(p"/assets/$file*") => Assets.versioned(path = "/public", file)
    case GET(p"/invokers") => Action {
      Results.Ok(html.invokers(invoker))
    }
  }
  bindInvokerTo(new InetSocketAddress(config.getInt("dispatcher.invokerPort")))
}
