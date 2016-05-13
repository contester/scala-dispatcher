package org.stingray.contester.dispatcher

import java.net.InetSocketAddress

import akka.actor.{ActorSystem, Props}
import com.spingo.op_rabbit.{ConnectionParams, RabbitControl}
import com.twitter.finagle.redis.Client
import com.typesafe.config.ConfigFactory
import controllers.Assets
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.internal.logging.{InternalLoggerFactory, Slf4JLoggerFactory}
import org.stingray.contester.common.{MemcachedObjectCache, TestingStore}
import org.stingray.contester.engine.InvokerSimpleApi
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.polygon._
import org.stingray.contester.problems.SimpleProblemDb
import org.stingray.contester.rpc4.ServerPipelineFactory
import org.stingray.contester.testing.SolutionTester
import org.stingray.contester.utils.CachedHttpService
import play.api.Logger

object DispatcherServer extends App {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  private val config = ConfigFactory.load()

  private val objectCache = new MemcachedObjectCache(config.getString("cache.host"))

  private val invoker = new InvokerRegistry("contester")

  val simpleDb =
    if (config.hasPath("simpledb")) {
      Some(SimpleProblemDb(config.getString("simpledb")))
    } else None

  val invokerApi = new InvokerSimpleApi(invoker, objectCache)
  val tester = new SolutionTester(invokerApi)

  val ioGroup = new NioEventLoopGroup()

  private def bindInvokerTo(socket: InetSocketAddress) = {
    val bs = new ServerBootstrap()
    .group(ioGroup).channel(classOf[NioServerSocketChannel])
        .childHandler(new ServerPipelineFactory[SocketChannel](invoker))

    bs.bind(socket).sync()
  }

  implicit val actorSystem = ActorSystem("such-system")

  Logger.info("Initializing dispatchers")

  val polygons = Polygons.fromConfig(config.getConfig("polygons").root())

 val polygonClient = PolygonClient(
   PolygonFilter(AuthPolygonMatcher(polygons.values).apply) andThen CachedHttpService,
   Client(config.getString("redis")), polygons, simpleDb.get, invokerApi)

  import slick.driver.MySQLDriver.api._

  import scala.collection.JavaConversions._

  for (name <- config.getStringList("dispatcher.standard"); if config.hasPath(name + ".dbnext")) yield {
    val db = Database.forConfig(s"${name}.dbnext")
    val ts = TestingStore(simpleDb.get.baseUrl, name)
    val rabbitMq = actorSystem.actorOf(Props(classOf[RabbitControl], ConnectionParams.fromConfig(config.getConfig(s"$name.op-rabbit"))))
    new DbDispatcher(db, polygonClient,tester,ts,rabbitMq)
  }

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
        val ts = TestingStore(simpleDb.get.baseUrl, "school.sgu.ru/moodle")
        val dispatcher = new MoodleDispatcher(db, simpleDb.get, tester, ts)
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
