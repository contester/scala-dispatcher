package org.stingray.contester.dispatcher

import com.twitter.finagle.Service
import com.twitter.finagle.builder.ServerBuilder
import com.twitter.finagle.http.{HttpMuxer, Http}
import com.twitter.util.Future
import grizzled.slf4j.Logging
import java.net.{URI, InetSocketAddress}
import org.apache.commons.io.IOUtils
import org.fusesource.scalate.layout.DefaultLayoutStrategy
import org.jboss.netty.buffer.ChannelBuffers.copiedBuffer
import org.jboss.netty.handler.codec.http.HttpResponseStatus.{OK, NOT_FOUND}
import org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1
import org.jboss.netty.handler.codec.http.{HttpMethod, DefaultHttpResponse, HttpResponse, HttpRequest}
import org.jboss.netty.util.CharsetUtil.UTF_8

object StatusPageBuilder extends Service[HttpRequest, HttpResponse] with Logging {
  val templateEngine: Function2[String, Map[String, Any], String] = {
    import org.fusesource.scalate.TemplateEngine
    val templatePath = getClass.getResource("/templates").getPath
    val e = new TemplateEngine(List(new java.io.File(templatePath)))
    e.allowReload = false
    e.layoutStrategy =  new DefaultLayoutStrategy(e, "layouts/default.ssp")
    e.layout(_, _)
  }
  val data = collection.mutable.HashMap[String, Any]()

  def getData: Map[String, Any] = data.toMap

  def status(request: HttpRequest, path: String) =
    Future {
      val response = new DefaultHttpResponse(HTTP_1_1, OK)
      val t = path match {
        case "/Invoker" => "invokers/InvokerRegistry"
        case _ => "Default"
      }

      val content = templateEngine("org/stingray/contester/" + t + ".ssp", getData)
      response.setContent(copiedBuffer(content, UTF_8))
      response
    }

  def admin(request: HttpRequest, path: String) = {
    val response = new DefaultHttpResponse(HTTP_1_1, OK)
    Future.value(response)
  }

  def apply(request: HttpRequest) = {
    val path = StaticServer.normalize(new URI(request.getUri).getPath)
    request.getMethod match {
      case HttpMethod.GET => status(request, path)
      case HttpMethod.POST if (path.startsWith("/admin/")) => admin(request, path)
      case _ => Future.value(new DefaultHttpResponse(HTTP_1_1, NOT_FOUND))
    }
  }.onFailure(error("Http Render", _))
}

object StaticServer extends Service[HttpRequest, HttpResponse] {
  def apply(request: HttpRequest): Future[HttpResponse] =
    Future {
      val path = normalize(new URI(request.getUri).getPath)
      val contents = Option(getClass.getResourceAsStream(path))
      contents.map { c =>
        val response = new DefaultHttpResponse(HTTP_1_1, OK)
        response.setContent(copiedBuffer(IOUtils.toByteArray(c)))
        response
      }.getOrElse(new DefaultHttpResponse(HTTP_1_1, NOT_FOUND))
    }

  def normalize(path: String) = {
    val suffix = if (path.endsWith("/")) "/" else ""
    val p = path.split("/") filterNot(_.isEmpty) mkString "/"
    "/" + p + suffix
  }
}


object HttpStatus {
  def bind(port: Int) = {
    HttpMuxer.addHandler("assets/", StaticServer)
    HttpMuxer.addHandler("", StatusPageBuilder)
    ServerBuilder()
      .codec(Http())
      .bindTo(new InetSocketAddress(port))
      .name("httpserver")
      .build(HttpMuxer)
  }
}
