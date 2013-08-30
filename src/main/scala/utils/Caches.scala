package org.stingray.contester.utils

import com.twitter.util.{Await, Future}
import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.google.common.util.concurrent.{SettableFuture, ListenableFuture}
import java.util.concurrent.TimeUnit

/**
 * Interface for asynchronous caches.
 *
 * @tparam KeyType Type for keys.
 * @tparam ValueType Type for values.
 */
trait ValueCache[KeyType, ValueType] {
  def get(key: KeyType): Future[Option[ValueType]]
  def put(key: KeyType, value: ValueType): Future[Unit]
}

abstract class ScannerCache2[KeyType, ValueType, RemoteType] {
  def cache: ValueCache[KeyType, RemoteType]

  def fetch(key: KeyType): Future[RemoteType]

  object NearCacheReloader extends CacheLoader[KeyType, Future[ValueType]] {
    def load(key: KeyType): Future[ValueType] =
      nearFetch(key)

    override def reload(key: KeyType, oldValue: Future[ValueType]): ListenableFuture[Future[ValueType]] = {
      val result = SettableFuture.create[Future[ValueType]]()
      if (oldValue.isDefined) {
        fetchSingleFlight(key).map(transform)
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

  private def nearFetch(key: KeyType) =
    cache.get(key).flatMap { v =>
      v.map(x => Future.value(transform(x)))
        .getOrElse(fetchSingleFlight(key).map(transform))
    }

  def transform(x: RemoteType): ValueType

  private val nearFutureCache = CacheBuilder.newBuilder()
    .expireAfterAccess(300, TimeUnit.SECONDS)
    .refreshAfterWrite(60, TimeUnit.SECONDS)
    .build(NearCacheReloader)

  private val farSerial = new SerialHash[KeyType, RemoteType]

  def apply(key: KeyType) =
    nearFutureCache.get(key)
}

