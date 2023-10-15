package org.stingray.contester.dispatcher

import java.net.InetSocketAddress

import akka.actor.{ActorSystem, Props}
import com.spingo.op_rabbit.{ConnectionParams, RabbitControl}
import com.twitter.finagle.redis.Client
import com.typesafe.config.ConfigFactory
import controllers.Assets
import info.faljse.SDNotify.SDNotify
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.internal.logging.{InternalLoggerFactory, Slf4JLoggerFactory}
import org.stingray.contester.common.TestingStore
import org.stingray.contester.engine.InvokerSimpleApi
import org.stingray.contester.invokers.InvokerRegistry
import org.stingray.contester.polygon._
import org.stingray.contester.problems.SimpleProblemDb
import org.stingray.contester.rpc4.ServerPipelineFactory
import org.stingray.contester.testing.{CustomTester, SolutionTester}
import org.stingray.contester.utils.CachedHttpService
import play.api.{Logger, Logging}

object DispatcherServer extends App with Logging {
  InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)

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
    logger.info(s"invoker channel bound to ${socket.getAddress}:${socket.getPort}")
  }

  implicit val actorSystem = ActorSystem("such-system")

  logger.info("Initializing dispatchers")

  val polygons = Polygons.fromConfig(config.getConfig("polygons").root())
  val reportbase = config.getString("reporting.base")


  import slick.jdbc.MySQLProfile.api._

  import scala.collection.JavaConverters._

  val dispatchers =
    if (config.hasPath("dispatcher.standard")) {
      val polygonClient = PolygonClient(
        PolygonFilter(AuthPolygonMatcher(polygons.values).apply) andThen CachedHttpService,
        Client(config.getString("redis")), polygons, simpleDb.get, invokerApi)

      for (name <- config.getStringList("dispatcher.standard").asScala; if config.hasPath(name + ".dbnext")) yield {
        val db = Database.forConfig(s"${name}.dbnext")
        val ts = TestingStore("filer:" + simpleDb.get.baseUrl + "fs/", name)
        val rabbitMq = actorSystem.actorOf(Props(classOf[RabbitControl], ConnectionParams.fromConfig(config.getConfig(s"$name.op-rabbit"))))
        new DbDispatcher(db, polygonClient, tester, custom, ts, rabbitMq, reportbase, simpleDb.get.client, simpleDb.get.baseUrl)
      }
    } else Nil

  val moodles =
  if (config.hasPath("dispatcher.moodles")) {
    for (name <- config.getStringList("dispatcher.moodles").asScala; if config.hasPath(name + ".dbnext")) yield {
        val db = Database.forConfig(s"${name}.dbnext")
        val ts = TestingStore("filer:"+simpleDb.get.baseUrl + "fs/", "school.sgu.ru/moodle")
        val dispatcher = new MoodleDispatcher(db, simpleDb.get, tester, ts)
        logger.info(s"starting actor for ${name}")
        actorSystem.actorOf(MoodleTableScanner.props(db, dispatcher))
    }
  } else Nil

  logger.info("Starting serving")

  import play.api.mvc._
  import play.api.routing.sird._
  import play.core.server._

  val server = NettyServer.fromRouterWithComponents(
    ServerConfig(
      port = Some(config.getInt("dispatcher.port")),
    )
  ) { components =>

    import components.{ defaultActionBuilder => Action }
    {
      // case GET(p"/assets/$file*") => Assets.versioned(path = "/public", file)
      case GET(p"/invokers") => Action {
        Results.Ok(html.invokers(invoker))
      }
    }
  }

  bindInvokerTo(new InetSocketAddress(config.getInt("dispatcher.invokerPort")))

  SDNotify.sendNotify()
}
