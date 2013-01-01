package org.stingray.contester.utils

import com.twitter.util.Future
import collection.mutable

class SerialHash[KeyType, ValueType] extends Function2[KeyType, () => Future[ValueType], Future[ValueType]] {
  private val data = new mutable.HashMap[KeyType, Future[ValueType]]()

  private[this] def removeKey(key: KeyType) =
    synchronized {
      data.remove(key)
    }

  def apply(key: KeyType, get: () => Future[ValueType]): Future[ValueType] =
    synchronized {
      data.getOrElseUpdate(key, get().ensure(() => removeKey(key)))
    }
}


