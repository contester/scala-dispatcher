package org.stingray.contester.common

import com.twitter.util.{Time, Future}
import org.jboss.netty.buffer.ChannelBuffer
import com.twitter.finagle.MemcachedClient
import grizzled.slf4j.Logging

trait ObjectCache {
  def cacheGet(key: String): Future[Option[ChannelBuffer]]
  def cacheSet(key: String, value: ChannelBuffer, expiry: Option[Time]): Future[Unit]
}

class MemcachedObjectCache(host: String) extends ObjectCache with Logging {
  val client = MemcachedClient.newRichClient(host)

  def cacheGet(key: String): Future[Option[ChannelBuffer]] = {
    trace("Get: " + key)
    client.get(key)
  }

  def cacheSet(key: String, value: ChannelBuffer, expiry: Option[Time]=None): Future[Unit] = {
    trace("Set: " + key)
    if (expiry.isDefined)
      client.set(key, 0, expiry.get, value)
    else
      client.set(key, value)
  }
}