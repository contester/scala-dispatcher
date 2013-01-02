package org.stingray.contester.utils

import com.twitter.util.{Try, Future}
import collection.mutable

class SerialHash[KeyType, ValueType] extends Function2[KeyType, () => Future[ValueType], Future[ValueType]] {
  private val data = new mutable.HashMap[KeyType, Future[ValueType]]()

  private[this] def removeKey(key: KeyType, v: Try[ValueType]) = {
    synchronized {
      data.remove(key)
    }
    Future.const(v)
  }

  def apply(key: KeyType, get: () => Future[ValueType]): Future[ValueType] =
    synchronized {
      data.getOrElseUpdate(key, get().transform(removeKey(key, _)))
    }
}

trait CacheFunctions[KeyType, ValueType] {
  def get(key: KeyType): Future[Option[ValueType]]
  def set(key: KeyType, value: ValueType): Future[Unit]
}

class ScannerCache[KeyType, ValueType](cache: CacheFunctions[KeyType, ValueType], farEnd: Function[KeyType, Future[ValueType]]) extends Function[KeyType, Future[ValueType]] {
  val localCache = new mutable.HashMap[KeyType, ValueType]()
  val serialHash = new SerialHash[KeyType, ValueType]()

  private[this] def getValue(key: KeyType) =
    cache.get(key).flatMap { optVal =>
      optVal.map(Future.value(_)).getOrElse(farEnd(key).flatMap(x => cache.set(key, x).map(_ => x)))
    }

  private[this] def setLocal(key: KeyType, value: ValueType) =
    synchronized {
      localCache(key) = value
    }

  private[this] def setKeys(keyset: Set[KeyType]) =
    synchronized {
      localCache.filterKeys(keyset)
    }

  def apply(key: KeyType): Future[ValueType] =
    synchronized {
      localCache.get(key).map(Future.value(_))
        .getOrElse(serialHash(key,() => getValue(key).onSuccess(setLocal(key, _))))
    }

  def scan(keys: Iterable[KeyType]) =
    Future.collect(keys.map(apply(_)).toSeq).onSuccess { vals =>
      setKeys(keys.toSet)
    }

}

case class CallbackCache[KeyType, ValueType](getFn: Function[KeyType, Future[Option[ValueType]]],
                                        setFn: Function2[KeyType, ValueType, Future[Unit]])
  extends CacheFunctions[KeyType, ValueType] {
  def get(key: KeyType): Future[Option[ValueType]] =
    getFn(key)

  def set(key: KeyType, value: ValueType): Future[Unit] =
    setFn(key, value)
}