package org.stingray.contester.utils

import java.net.{URI, URISyntaxException}
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.twitter.finagle.{Http, Service}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.{Future, StorageUnit}
import grizzled.slf4j.Logging
import org.apache.http.HttpHost
import org.apache.http.client.utils.URIUtils

object CachedConnectionHttpService extends Function[HttpHost, Service[Request, Response]] with Logging {
  private final val maxResponseSize = new StorageUnit(64*1024*1024)
  private object PolygonClientCacheLoader extends CacheLoader[HttpHost, Service[Request, Response]] {
    import com.twitter.conversions.time._
    private def common(key: HttpHost) =
      Http.client.withSessionPool.maxSize(1)
        .withTransport.connectTimeout(5 seconds)
        .withRequestTimeout(5 seconds)
        .withMaxResponseSize(maxResponseSize)

    private[this] def hostString(key: HttpHost) =
      if (key.getPort != -1)
        key.toHostString
      else {
        val p = key.getSchemeName match {
          case "https" => 443
          case "http" => 80
          case _ => 80
        }
        s"${key.getHostName}:$p"
      }

    def load(key: HttpHost): Service[Request, Response] = {
      val settings = common(key)
      val s2 = if (key.getSchemeName == "https")
        settings.withTransport.tls(SSLContext.getDefault()).withTls(key.getHostName)
      else settings
      s2.newService(hostString(key))
    }
  }

  private val connCache = CacheBuilder.newBuilder()
    .expireAfterAccess(30, TimeUnit.MINUTES)
    .build(PolygonClientCacheLoader)

  def apply(key: HttpHost): Service[Request, Response] =
    connCache.get(key)

  def apply(uri: URI): Service[Request, Response] =
    apply(URIUtils.extractHost(uri))
}

case class RequestWithURI(req: Request, uri: URI)

object CachedHttpService extends Service[RequestWithURI, Response] with Logging {
  override def apply(request: RequestWithURI): Future[Response] = {
    trace(s"apply($request)")
    CachedConnectionHttpService(request.uri)(request.req).onFailure(error(s"apply($request)", _))
  }
}

object URIParse {
  def apply(source: String): Option[URI] = {
    import scala.util.control.Exception._

    catching(classOf[URISyntaxException]) opt new URI(source)
  }
}