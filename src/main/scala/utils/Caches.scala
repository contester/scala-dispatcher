package org.stingray.contester.utils

import com.twitter.util.{Future, Time}
import com.twitter.finagle.{Service, Filter}

trait CachableRequest {
  def isRefresh: Boolean
}

trait CachableValue {
  def timestamp: Time
}

trait Cache[Key, Value] {
  def get(key: Key): Future[Option[Value]]
  def put(key: Key, value: Value): Future[Unit]
}

class CachingFilter[K <: CachableRequest, V <: CachableValue](cache: Cache[K, V]) extends Filter[K, V, K, V] {
  private def refresh(request: K, service: Service[K, V]) =
    service(request).flatMap { value =>
      cache.put(request, value).map(_ => value)
    }

  def apply(request: K, service: Service[K, V]): Future[V] =
    if (request.isRefresh)
      refresh(request, service)
    else
      cache.get(request).flatMap(_.map(Future.value).getOrElse(refresh(request, service)))
}