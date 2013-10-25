package org.stingray.contester.dispatcher

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Response, Request}
import com.twitter.util.Future
import org.jboss.netty.handler.codec.http.HttpResponseStatus.OK
import org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1
import org.jboss.netty.util.CharsetUtil.UTF_8
import org.fusesource.scalate.TemplateEngine
import org.jboss.netty.buffer.ChannelBuffers.copiedBuffer

class DynamicServer(templateEngine: TemplateEngine, template: String, attributes: Map[String, Any])
    extends Service[Request, Response] {
  def apply(request: Request): Future[Response] = {
    Future {
      request.response.setProtocolVersion(HTTP_1_1)
      request.response.setStatus(OK)
      request.response.setContent(copiedBuffer(templateEngine.layout(
          template, attributes ++ Map("request" -> request)), UTF_8))
      request.response
    }
  }
}
