package org.stingray.contester.utils

import com.twitter.util.{Try, Future}
import com.google.common.cache.{CacheLoader, Cache, CacheBuilder}
import java.util.concurrent.Callable
import com.google.common.util.concurrent.{SettableFuture, ListenableFuture}
import scala.collection.mutable

/** Memoizator/serializator makes sure there's only one outstanding async operation per key.
  *
  * Typical usage will be to avoid spamming backend with requests for the same entity.
  *
  * @tparam KeyType Type of the key
  * @tparam ValueType Type of the value
  */
class SerialHash[KeyType, ValueType] extends Function2[KeyType, () => Future[ValueType], Future[ValueType]] {
  private val data: Cache[KeyType, Future[ValueType]] = CacheBuilder.newBuilder().build()

  /** Removes the key and returns the value.
    *
    * @param key Key to remove.
    * @param v Value to return.
    * @return v.
    */
  private[this] def removeKey(key: KeyType, v: Try[ValueType]) = {
    data.invalidate(key)
    Future.const(v)
  }

  /** If the operation with key is already running, return its future. Otherwise, start it (by using get()) and
    * return its future.
    * @param key Key to use.
    * @param get Function to start the async op.
    * @return Result of the async op.
    */
  def apply(key: KeyType, get: () => Future[ValueType]): Future[ValueType] =
    data.get(key, new Callable[Future[ValueType]] {
      def call(): Future[ValueType] = {
        val result = get()
        result.transform(removeKey(key, _))
      }
    })
}

class SimpleSerialHash[KeyType, ValueType](underlying: (KeyType) => Future[ValueType]) extends CacheLoader[KeyType, Future[ValueType]] {
  def load(key: KeyType): Future[ValueType] = ???
}

class SimpleCache[KeyType, ValueType](underlying: (KeyType) => Future[ValueType]) extends CacheLoader[KeyType, Future[ValueType]] {
  def load(key: KeyType): Future[ValueType] =
    underlying(key)

  override def reload(key: KeyType, oldValue: Future[ValueType]): ListenableFuture[Future[ValueType]] = {
    val result = new SettableFuture[Future[ValueType]]
    underlying(key)
      .onSuccess(v => result.set(Future.value(v)))
      .onFailure(e => result.setException(e))
    result
  }
}

// CachingLayer
//   - get
//   - farGet
//   - put
//   - reload

abstract class CachingLayer[KeyType, NearType, FarType] extends Function[KeyType, Future[NearType]] {
  def nearGet(key: KeyType): Future[Option[NearType]]
  def nearPut(key: KeyType, value: FarType): Future[NearType]
  def farGet(key: KeyType): Future[FarType]

  /** This gets called to fetch value from storage and put it to L2.
    *
    * @param key Key to fetch
    * @return Value, converted after putting to L2.
    */
  def refresh(key: KeyType): Future[NearType] =
    farGet(key).flatMap(x => nearPut(key, x))

  def apply(key: KeyType): Future[NearType] =
    nearGet(key).flatMap { optValue =>
      optValue.map(Future.value(_))
        .getOrElse(refresh(key))
    }
}

/** Interesting class to build L1/L2 caches with fetch routine.
  * There's a "far place" (original data source), and "near place" (L2 cache). There's also local internal cache.
  * On get, we look for value in local cache, then in L2 cache, then in data source.
  * We also support different types for data in original and L2.
  *
  * @tparam KeyType Keys to look up are of this type.
  * @tparam ValueType Values that we return.
  * @tparam SomeType Type of data in the data source
  */
abstract class ScannerCache[KeyType, ValueType, SomeType] extends Function[KeyType, Future[ValueType]] {
  /** Get data from L2, or return None if there's nothing.
    *
    * @param key Key to look up.
    * @return Some(value) or None.
    */
  def nearGet(key: KeyType): Future[Option[ValueType]]

  /** Put data to L2, and return it converted to ValueType.
    *
    * @param key Key.
    * @param value Value to put.
    * @return Converted/translated value.
    */
  def nearPut(key: KeyType, value: SomeType): Future[ValueType]

  /** Fetch data from the original data source.
    *
    * @param key Key.
    * @return Data from data source.
    */
  def farGet(key: KeyType): Future[SomeType]

  /** If true, scan() always fetches the data from source.
    *
    */
  val farScan = false

  /** L1 cache.
    *
    */
  private val localCache = new mutable.HashMap[KeyType, ValueType]()

  /** Serializer for gets.
    *
    */
  private val serialHash = new SerialHash[KeyType, ValueType]()

  /** This gets called to fetch value from storage and put it to L2.
    *
    * @param key Key to fetch
    * @return Value, converted after putting to L2.
    */
  private[this] def fetchValue(key: KeyType) = {
    farGet(key).flatMap(x => nearPut(key, x))
  }

  /** Get value from L2, or fetch it from the storage.
    *
    * @param key Key to fetch.
    * @return Value.
    */
  private[this] def getValue(key: KeyType) =
    nearGet(key).flatMap { optVal =>
      optVal.map { v =>
        Future.value(v)
      }.getOrElse(fetchValue(key))
    }

  /** Set value in local cache.
    *
    * @param key Key.
    * @param value Value.
    */
  private[this] def setLocal(key: KeyType, value: ValueType): Unit =
    synchronized {
      localCache(key) = value
    }

  /** Throw everything away from local cache, except for keyset.
    *
    * @param keyset Keys to keep
    */
  private[this] def setKeys(keyset: Set[KeyType]): Unit =
    synchronized {
      localCache.retain((k, v) => keyset(k))
    }

  /** Use given function to fetch a value for a given key, use serialHash with it.
    *
    * @param kv Fetch function to use.
    * @param key Key.
    * @return Fetched value.
    */
  private[this] def fetchAndSet(kv: KeyType => Future[ValueType], key: KeyType) =
    serialHash(key, () => kv(key).onSuccess(setLocal(key, _)))

  /** Transparent fetch value function.
    *
    * @param key Key to fetch.
    * @return Value.
    */
  def apply(key: KeyType): Future[ValueType] =
    synchronized {
      localCache.get(key).map { v =>
        Future.value(v)
      }.getOrElse(fetchAndSet(getValue, key))
    }

  /** Fetch values for a set of keys, discarding those not in the set.
    *
    * @param keys Set of keys.
    * @return Values.
    */
  def scan(keys: Iterable[KeyType]) =
    Future.collect(keys.map(fetchAndSet(if (farScan) fetchValue else getValue, _)).toSeq).onSuccess { vals =>
      setKeys(keys.toSet)
    }
}

object ScannerCache {
  /** Construct a ScannerCache with get/put functions defined.
    *
    * @param nearGetFn Function to get value from L2 cache.
    * @param nearPutFn Function to put value to L2 cache.
    * @param farGetFn Function to get value from source.
    * @tparam KeyType Key type.
    * @tparam ValueType Value type.
    * @return ScannerCache instance.
    */
  def apply[KeyType, ValueType](nearGetFn: KeyType => Future[Option[ValueType]],
                                nearPutFn: (KeyType, ValueType) => Future[Unit],
                                farGetFn: KeyType => Future[ValueType]): ScannerCache[KeyType, ValueType, ValueType] =
    new ScannerCache[KeyType, ValueType, ValueType] {
      def nearGet(key: KeyType): Future[Option[ValueType]] = nearGetFn(key)

      def nearPut(key: KeyType, value: ValueType): Future[ValueType] = nearPutFn(key, value).map(_ => value)

      def farGet(key: KeyType): Future[ValueType] = farGetFn(key)
    }
}