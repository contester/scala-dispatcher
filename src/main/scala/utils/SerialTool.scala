package org.stingray.contester.utils

import java.util.concurrent.ConcurrentHashMap

import com.twitter.util.{Future, Promise, Try}

/** Memoizator/serializator makes sure there's only one outstanding async operation per key.
  *
  * Typical usage will be to avoid spamming backend with requests for the same entity.
  *
  * @tparam KeyType Type of the key
  * @tparam ValueType Type of the value
  */
class SerialHash[KeyType, ValueType] extends ((KeyType, () => Future[ValueType]) => Future[ValueType]) {
  /** Map with outstanding requests. Synchronized.
    */
  private val data = {
    import scala.collection.JavaConverters._
    (new ConcurrentHashMap[KeyType, Future[ValueType]]()).asScala
  }

  /** If the operation with key is already running, return its future. Otherwise, start it (by using get()) and
    * return its future.
    *
    * @param key Key to use.
    * @param get Function to start the async op.
    * @return Result of the async op.
    */
  def apply(key: KeyType, get: () => Future[ValueType]): Future[ValueType] = {
    val p = Promise[ValueType]
    data.putIfAbsent(key, p) match {
      case None =>
        p.become(get().ensure(data.remove(key, p)))
        p
      case Some(v) => v
    }
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
  def parse(key: KeyType, what: SomeType): ValueType

  /** Get data from L2, or return None if there's nothing.
    *
    * @param key Key to look up.
    * @return Some(value) or None.
    */
  def nearGet(key: KeyType): Future[Option[SomeType]]

  /** Put data to L2.
    *
    * @param key Key.
    * @param value Value to put.
    */
  def nearPut(key: KeyType, value: SomeType): Future[Unit]

  /** Fetch data from the original data source.
    *
    * @param key Key.
    * @return Data from data source.
    */
  def farGet(key: KeyType): Future[SomeType]

  /** L1 cache.
    *
    */
  private val localCache = {
    import scala.collection.JavaConverters._
    (new ConcurrentHashMap[KeyType, Future[ValueType]]()).asScala
  }

  /** Serializer for gets.
    *
    */
  private val serialHash = new SerialHash[KeyType, ValueType]()

  def refresh(key: KeyType) = {
    val v = refresh0(key)
    v.onSuccess { _ => localCache.put(key, v) }
    v
  }

  private[this] def refresh0(key: KeyType) =
    serialHash(key, () => farGetAndParse(key))

  private[this] def farGetAndParse(key: KeyType) =
    farGet(key).map { x =>
      val p = parse(key, x)
      nearPut(key, x)
      p
    }

  private[this] def nearGetAndParse(key: KeyType): Future[ValueType] =
    nearGet(key).flatMap {
      case Some(v) => Future.const(Try(parse(key, v)))
      case None => refresh0(key)
    }

  def apply(key: KeyType): Future[ValueType] = {
    val p = Promise[ValueType]
    localCache.putIfAbsent(key, p) match {
      case Some(v) => v
      case None =>
        p.become(nearGetAndParse(key))
        p
    }
  }
}

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

object ScannerCache {
  def apply[A, B, C](parseFunc: (A, C) => B, nearGetFunc: A => Future[Option[C]], nearPutFunc: (A, C) => Future[Unit],
                     farGetFunc: A => Future[C]): ScannerCache[A, B, C] = new ScannerCache[A, B, C] {
    override def parse(key: A, what: C): B = parseFunc(key, what)
    override def farGet(key: A): Future[C] = farGetFunc(key)
    override def nearGet(key: A): Future[Option[C]] = nearGetFunc(key)
    override def nearPut(key: A, value: C): Future[Unit] = nearPutFunc(key, value)
  }
}