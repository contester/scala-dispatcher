package org.stingray.contester.polygon

import com.twitter.finagle.redis.Client
import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.io.Charsets
import com.twitter.util.Future
import org.stingray.contester.utils.ValueCache

trait ProvidesRedisKey {
  def redisKey: String
}

class RedisStore(client: Client) extends ValueCache[ProvidesRedisKey, String] {
  override def get(key: ProvidesRedisKey): Future[Option[String]] =
    client.get(StringToChannelBuffer(key.redisKey)).map(_.map(_.toString(Charsets.Utf8)))

  override def put(key: ProvidesRedisKey, value: String): Future[Unit] =
    client.set(StringToChannelBuffer(key.redisKey), StringToChannelBuffer(value))
}