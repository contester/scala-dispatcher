package org.stingray.contester.dispatcher

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
import org.stingray.contester.common.{MemcachedObjectCache, MongoDBInstance}
import org.stingray.contester.polygon._
import org.stingray.contester.problems.CommonProblemDb
import org.fusesource.scalate.layout.DefaultLayoutStrategy
import com.twitter.finagle.http.{Request, Http, HttpMuxer}
import com.twitter.finagle.builder.ServerBuilder
import org.stingray.simpleweb.StaticService

object DispatcherServer extends App {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  def createDbConfig(conf: Configuration) =
    new DbConfig(conf)

  val templateEngine = {
    import org.fusesource.scalate.TemplateEngine
    val templatePath = getClass.getResource("/templates").getPath
    val e = new TemplateEngine(List(new java.io.File(templatePath)))
    e.allowReload = false
    e.layoutStrategy =  new DefaultLayoutStrategy(e, "layouts/default.ssp")
    e
  }

  val config = Configuration.load("dispatcher.conf")
  val mongoUrl = config[String]("pdb.mongoUrl")
  val mongoDb = MongoDBInstance(mongoUrl).right.get

  val objectCache = new MemcachedObjectCache(config[String]("cache.host"))

  val polygonCache = new PolygonCache(mongoDb.db)
  val problemDb = new CommonProblemDb(mongoDb.db, mongoDb.objectStore)
  val invoker = new InvokerRegistry(mongoUrl)

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

  println("before dispatchers")

  val dispatchers =
    config.get[List[String]]("dispatcher.standard").map { names =>

      val polygonConfs = config.detach("polygons").detachAll
      val contestResolver = new ContestResolver(polygonConfs.mapValues(c => new URL(c[String]("url"))))

      val authFilter = new AuthPolygonFilter
      polygonConfs.foreach {
        case (shortName, polygonConf) =>
        authFilter.addPolygon(new PolygonBase(shortName, new URL(polygonConf[String]("url")), polygonConf[String]("username"), polygonConf[String]("password")))
      }

      val client = authFilter andThen BasicPolygonFilter andThen CachedConnectionHttpService
      val problems = new ProblemData(client, polygonCache, problemDb, invokerApi)
      val result = new DbDispatchers(problems, new File(config[String]("reporting.base")), tester, mongoDb.objectStore, contestResolver(_))

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
        new MoodleDispatcher(createDbConfig(config.detach(name)).createConnectionPool, problemDb, tester, mongoDb.objectStore)
      }.foreach(_.start)
    }

  println("after dispatchers")

  HttpMuxer.addRichHandler("assets/", new StaticService[Request])
  HttpMuxer.addRichHandler("invokers", new DynamicServer(
      templateEngine, "org/stingray/contester/invokers/InvokerRegistry.ssp", Map("invoker" -> invoker)))
  bindInvokerTo(new InetSocketAddress(config[Int]("dispatcher.invokerPort", 9981)))

  val httpServer = ServerBuilder()
      .codec(Http())
      .bindTo(new InetSocketAddress(config[Int]("dispatcher.port")))
      .name("httpserver")
      .build(HttpMuxer)
}
