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
import org.stingray.contester.testing.{CustomTester, SolutionTester}
import org.stingray.contester.utils.CachedHttpService
import play.api.Logger

object DispatcherServer extends App {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  private val config = ConfigFactory.load()

  private val invoker = new InvokerRegistry("contester")

  private val simpleDb =
    if (config.hasPath("simpledb")) {
      Some(SimpleProblemDb(config.getString("simpledb")))
    } else None

  private val invokerApi = new InvokerSimpleApi(invoker)
  private val tester = new SolutionTester(invokerApi)
  private val custom = new CustomTester(invokerApi)

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
  val reportbase = config.getString("reporting.base")

 val polygonClient = PolygonClient(
   PolygonFilter(AuthPolygonMatcher(polygons.values).apply) andThen CachedHttpService,
   Client(config.getString("redis")), polygons, simpleDb.get, invokerApi)

  import slick.driver.MySQLDriver.api._

  import scala.collection.JavaConversions._

  val dispatchers =
  for (name <- config.getStringList("dispatcher.standard"); if config.hasPath(name + ".dbnext")) yield {
    val db = Database.forConfig(s"${name}.dbnext")
    val ts = TestingStore("filer:" + simpleDb.get.baseUrl + "fs/", name)
    val rabbitMq = actorSystem.actorOf(Props(classOf[RabbitControl], ConnectionParams.fromConfig(config.getConfig(s"$name.op-rabbit"))))
    new DbDispatcher(db, polygonClient, tester, custom, ts, rabbitMq, reportbase)
  }

  val moodles =
  if (config.hasPath("dispatcher.moodles")) {
    for (name <- config.getStringList("dispatcher.moodles"); if config.hasPath(name + ".dbnext")) yield {
        val db = Database.forConfig(s"${name}.dbnext")
        val ts = TestingStore("filer:"+simpleDb.get.baseUrl + "fs/", "school.sgu.ru/moodle")
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
