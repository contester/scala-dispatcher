package org.stingray.contester.common

import com.twitter.io.Buf
import com.twitter.util.{Future, Time}
import grizzled.slf4j.Logging
import com.google.protobuf.Message
import com.twitter.finagle.Memcached
import org.stingray.contester.utils.ProtobufTools

trait ObjectCache {
  def cacheGet(key: String): Future[Option[Buf]]
  def cacheSet(key: String, value: Buf, expiry: Option[Time]): Future[Unit]

  def maybeCached[S, I <: Message](key: String, expiry: Option[Time],
                                           fetch: => Future[S], wrap: (I) => S,
                                           unwrap: (S) => I)(implicit manifest: Manifest[I]): Future[S] =
    cacheGet(key).flatMap { optValue =>
      optValue
          .map(ProtobufTools.createProtobuf[I])
          .map(wrap)
          .map(Future.value)
          .getOrElse {
        fetch.onSuccess { value =>
          cacheSet(key, Buf.ByteArray.Shared(unwrap(value).toByteArray), expiry)
        }
      }
    }

}

class MemcachedObjectCache(host: String) extends ObjectCache with Logging {
  val client = Memcached.client.newRichClient(host)

  def cacheGet(key: String): Future[Option[Buf]] = {
    trace("Get: " + key)
    client.get(key)
  }

  def cacheSet(key: String, value: Buf, expiry: Option[Time]=None): Future[Unit] = {
    trace("Set: " + key)
    client.set(key, 0, expiry.getOrElse(Time.epoch), value)
  }
}