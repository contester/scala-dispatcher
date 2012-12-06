package org.stingray.contester.messaging

import com.rabbitmq.client._
import org.streum.configrity.Configuration

object AMQ {
  def createConnection(conf: Configuration) = {
    val f = new ConnectionFactory()
    f.setUsername(conf[String]("username"))
    f.setPassword(conf[String]("password"))
    f.setVirtualHost(conf[String]("vhost"))
    f.newConnection(Array(new Address(conf[String]("server"), 5672)))
  }
}