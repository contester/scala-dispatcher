package org.stingray.contester.polygon

import com.twitter.util.Future
import org.stingray.contester.utils.ValueCache
import java.net.URL

import com.twitter.finagle.redis.Client
import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.io.Charsets

trait PolygonCacheKey {
  def url: URL
}

trait PolygonContestKey extends PolygonCacheKey with ProvidesRedisKey {
  def redisKey = s"rawContest/${url.toString}"
}
trait PolygonProblemKey extends PolygonCacheKey {
  def revision: Option[Int]
}

trait ProvidesRedisKey {
  def redisKey: String
}

class RedisStore(client: Client) extends ValueCache[ProvidesRedisKey, String] {
  override def get(key: ProvidesRedisKey): Future[Option[String]] =
    client.get(StringToChannelBuffer(key.redisKey)).map(_.map(_.toString(Charsets.Utf8)))

  override def put(key: ProvidesRedisKey, value: String): Future[Unit] =
    client.set(StringToChannelBuffer(key.redisKey), StringToChannelBuffer(value))
}