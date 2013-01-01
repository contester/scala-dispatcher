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


