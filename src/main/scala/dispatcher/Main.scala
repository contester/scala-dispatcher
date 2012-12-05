import grizzled.slf4j.Logging
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.logging.{Slf4JLoggerFactory, InternalLoggerFactory}
import org.stingray.contester._
import org.stingray.contester.common.ProblemDb
import org.stingray.contester.dispatcher._
import org.stingray.contester.polygon.PolygonClient
import org.stingray.contester.rpc4.ServerPipelineFactory
import org.streum.configrity.Configuration
import rpc.AMQ

object Main extends App with Logging {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  def createDbConfig(conf: Configuration) =
    DbConfig(conf[String]("host"),
      conf[String]("db"),
      conf[String]("username"),
      conf[String]("password"))

  val config = Configuration.load("dispatcher.conf")
  val mHost = config[String]("pdb.mhost")
  val amqclient = AMQ.createConnection(config.detach("messaging"))

  val httpStatus = HttpStatus.bind(config[Int]("dispatcher.port"))

  val client = PolygonClient(config.detach("polygon"))
  val pdb = ProblemDb(mHost, client)
  val invoker = new Invoker(mHost)
  StatusPageBuilder.data("invoker") = invoker

  val problems = new ProblemData(client, pdb, invoker)

  val dispatchers = new DbDispatchers(problems, new File(config[String]("reporting.base")), invoker, amqclient.createChannel(), pdb)

  val sf = new NioServerSocketChannelFactory(
    Executors.newCachedThreadPool(),
    Executors.newCachedThreadPool())
  val bs = new ServerBootstrap(sf)
  bs.setPipelineFactory(new ServerPipelineFactory(invoker))
  bs.bind(new InetSocketAddress(9981))

  dispatchers.add(createDbConfig(config.detach("db")))
  if (config.contains("dbTest.db"))
    dispatchers.add(createDbConfig(config.detach("dbTest")))
}