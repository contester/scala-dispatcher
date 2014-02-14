package org.stingray.contester.common

import com.twitter.util.{Time, Future}
import org.jboss.netty.buffer.{ChannelBuffers, ChannelBuffer}
import com.twitter.finagle.MemcachedClient
import grizzled.slf4j.Logging
import com.google.protobuf.Message
import org.stingray.contester.utils.ProtobufTools

trait ObjectCache {
  def cacheGet(key: String): Future[Option[ChannelBuffer]]
  def cacheSet(key: String, value: ChannelBuffer, expiry: Option[Time]): Future[Unit]

  def maybeCached[S, I <: Message](key: String, expiry: Option[Time],
                                           fetch: => Future[S], wrap: (I) => S,
                                           unwrap: (S) => I)(implicit manifest: Manifest[I]): Future[S] =
    cacheGet(key).flatMap { optValue =>
      optValue
          .map(ProtobufTools.createProtobuf[I](_))
          .map(wrap)
          .map(Future.value)
          .getOrElse {
        fetch.onSuccess { value =>
          cacheSet(key, ChannelBuffers.wrappedBuffer(unwrap(value).toByteArray), expiry)
        }
      }
    }

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