package org.stingray.contester.utils

import com.twitter.util.{Try, Future}
import collection.mutable
import grizzled.slf4j.Logging

class SerialHash[KeyType, ValueType] extends Function2[KeyType, () => Future[ValueType], Future[ValueType]] with Logging {
  private val data = new mutable.HashMap[KeyType, Future[ValueType]]()

  private[this] def removeKey(key: KeyType, v: Try[ValueType]) = {
    synchronized {
      data.remove(key)
    }
    Future.const(v)
  }

  def apply(key: KeyType, get: () => Future[ValueType]): Future[ValueType] =
    synchronized {
      if (data.contains(key)) {
        trace("Enqueued: %s".format(key))
        data(key)
      } else
        data(key) = get()
        data(key).transform(removeKey(key, _))
    }
}

abstract class ScannerCache[KeyType, ValueType, SomeType] extends Function[KeyType, Future[ValueType]] with Logging {
  def nearGet(key: KeyType): Future[Option[ValueType]]
  def nearPut(key: KeyType, value: SomeType): Future[ValueType]
  def farGet(key: KeyType): Future[SomeType]

  val farScan = false

  val localCache = new mutable.HashMap[KeyType, ValueType]()
  val serialHash = new SerialHash[KeyType, ValueType]()

  private[this] def fetchValue(key: KeyType) = {
    trace("Miss: %s".format(key))
    farGet(key).flatMap(x => nearPut(key, x))
  }

  private[this] def getValue(key: KeyType) =
    nearGet(key).flatMap { optVal =>
      optVal.map { v =>
        trace("Near-hit: %s, result: %s".format(key, v))
        Future.value(v)
      }.getOrElse(fetchValue(key))
    }

  private[this] def setLocal(key: KeyType, value: ValueType) =
    synchronized {
      localCache(key) = value
    }

  private[this] def setKeys(keyset: Set[KeyType]) =
    synchronized {
      localCache.filterKeys(keyset)
    }

  private[this] def fetchAndSet(kv: KeyType => Future[ValueType], key: KeyType) =
    serialHash(key, () => kv(key).onSuccess(setLocal(key, _)))

  def apply(key: KeyType): Future[ValueType] =
    synchronized {
      localCache.get(key).map { v =>
        trace("Hit: %s, result: %s".format(key, v))
        Future.value(v)
      }.getOrElse(fetchAndSet(getValue, key))
    }

  def scan(keys: Iterable[KeyType]) =
    Future.collect(keys.map(fetchAndSet(if (farScan) fetchValue else getValue, _)).toSeq).onSuccess { vals =>
      setKeys(keys.toSet)
    }
}

object ScannerCache {
  def apply[KeyType, ValueType](nearGetFn: KeyType => Future[Option[ValueType]],
                                nearPutFn: (KeyType, ValueType) => Future[Unit],
                                farGetFn: KeyType => Future[ValueType]): ScannerCache[KeyType, ValueType, ValueType] =
    new ScannerCache[KeyType, ValueType, ValueType] {
      def nearGet(key: KeyType): Future[Option[ValueType]] = nearGetFn(key)

      def nearPut(key: KeyType, value: ValueType): Future[ValueType] = nearPutFn(key, value).map(_ => value)

      def farGet(key: KeyType): Future[ValueType] = farGetFn(key)
    }
}