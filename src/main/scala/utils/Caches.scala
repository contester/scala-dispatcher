package org.stingray.contester.utils

import com.twitter.util.{Await, Future}
import com.google.common.cache.{CacheBuilder, CacheLoader}
import org.stingray.contester.polygon.{PolygonClient, ContestDescription, ContestHandle}
import com.google.common.util.concurrent.{SettableFuture, ListenableFuture}
import scala.xml.XML
import java.util.concurrent.TimeUnit

trait ValueCache[KeyType, ValueType] {
  def get(key: KeyType): Future[Option[ValueType]]
  def put(key: KeyType, value: ValueType)
}

abstract class ScannerCache2[KeyType, ValueType, RemoteType] {
  def cache: ValueCache[KeyType, RemoteType]

  def fetch(key: KeyType): Future[RemoteType]

  object NearCacheReloader extends CacheLoader[KeyType, Future[ValueType]] {
    def load(key: KeyType): Future[ValueType] =
      nearFetch(key)

    override def reload(key: KeyType, oldValue: Future[ValueType]): ListenableFuture[Future[ValueType]] = {
      val result = new SettableFuture[Future[ContestDescription]]
      if (oldValue.isDefined) {
        farFetch(key).map(transform)
          .onSuccess {
          v =>
            result.set(if (v == Await.result(oldValue)) oldValue else Future.value(v))
        }
          .onFailure(e => result.setException(e))
      } else {
        result.set(oldValue)
      }
      result
    }
  }

  private def fetchSingleFlight(key: KeyType) =
    farSerial(key, () => fetch(key))

  private def nearFetch(key: ContestHandle) =
    cache.get(key).flatMap { v =>
      v.map(x => Future.value(transform(x)))
        .getOrElse(farFetch(key).map(transform))
    }

  private def transform(x: String) =
    (new ContestDescription(XML.loadString(x)))

  private val nearFutureCache = CacheBuilder.newBuilder()
    .expireAfterAccess(300, TimeUnit.SECONDS)
    .refreshAfterWrite(60, TimeUnit.SECONDS)
    .build(NearCacheReloader)

  private val farSerial = new SerialHash[KeyType, RemoteType]

  def apply(key: ContestHandle) =
    nearFutureCache.get(key)

}

