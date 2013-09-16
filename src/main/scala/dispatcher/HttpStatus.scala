package org.stingray.contester.dispatcher

import com.twitter.finagle.Service
import com.twitter.finagle.http.HttpMuxer
import com.twitter.util.Future
import java.net.URI
import org.apache.commons.io.IOUtils
import org.jboss.netty.handler.codec.http.HttpResponseStatus.{OK, NOT_FOUND}
import org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1
import org.jboss.netty.handler.codec.http.{DefaultHttpResponse, HttpResponse, HttpRequest}
import org.jboss.netty.util.CharsetUtil.UTF_8
import org.fusesource.scalate.TemplateEngine
import org.jboss.netty.buffer.ChannelBuffers.copiedBuffer

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

class DynamicServer(templateEngine: TemplateEngine, template: String, attributes: Map[String, Any])
    extends Service[HttpRequest, HttpResponse] {
  def apply(request: HttpRequest): Future[HttpResponse] = {
    Future {
      val response = new DefaultHttpResponse(HTTP_1_1, OK)
      response.setContent(copiedBuffer(templateEngine.layout(
          template, attributes ++ Map("request" -> request)), UTF_8))
      response
    }
  }
}

object HttpStatus {
  def addHandlers = {
    HttpMuxer.addHandler("assets/", StaticServer)
  }
}
