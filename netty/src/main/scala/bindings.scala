package unfiltered.netty

import unfiltered.{Async}
import unfiltered.response.{ResponseFunction, HttpResponse, Pass}
import unfiltered.request.{HttpRequest,POST,PUT,&,RequestContentType,Charset}
import java.net.URLDecoder
import org.jboss.netty.handler.codec.http._
import java.io.{BufferedReader, ByteArrayOutputStream, InputStreamReader}
import org.jboss.netty.buffer.{ChannelBuffers, ChannelBufferOutputStream,
  ChannelBufferInputStream}
import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.http.HttpVersion._
import org.jboss.netty.handler.codec.http.HttpResponseStatus._
import org.jboss.netty.handler.codec.http.{HttpResponse=>NHttpResponse,
                                           HttpRequest=>NHttpRequest}
import java.nio.charset.{Charset => JNIOCharset}
import unfiltered.Cookie
import scala.collection.JavaConverters._

object HttpConfig {
   val DEFAULT_CHARSET = "UTF-8"
}

class RequestBinding(msg: ReceivedMessage)
extends HttpRequest(msg) with Async.Responder[NHttpResponse] {
  private val req = msg.request
  lazy val params = queryParams ++ bodyParams
  def queryParams = req.getUri.split("\\?", 2) match {
    case Array(_, qs) => URLParser.urldecode(qs)
    case _ => Map.empty[String,Seq[String]]
  }
  def bodyParams = this match {
    case (POST(_) | PUT(_)) & RequestContentType(ct) if ct.contains("application/x-www-form-urlencoded") =>
      URLParser.urldecode(req.getContent.toString(JNIOCharset.forName(charset)))
    case _ => Map.empty[String,Seq[String]]
  }

  private def charset = Charset(this).getOrElse {
    HttpConfig.DEFAULT_CHARSET
  }
  lazy val inputStream = new ChannelBufferInputStream(req.getContent)
  lazy val reader = {
    new BufferedReader(new InputStreamReader(inputStream, charset))
  }

  def protocol = req.getProtocolVersion match {
    case HttpVersion.HTTP_1_0 => "HTTP/1.0"
    case HttpVersion.HTTP_1_1 => "HTTP/1.1"
  }
  def method = req.getMethod.toString.toUpperCase

  // todo should we call URLDecoder.decode(uri, charset) on this here?
  def uri = req.getUri

  def parameterNames = params.keySet.iterator
  def parameterValues(param: String) = params.getOrElse(param, Seq.empty)
  def headerNames = req.getHeaderNames.iterator.asScala
  def headers(name: String) = req.getHeaders(name).iterator.asScala

  def isSecure = msg.context.getPipeline.get(classOf[org.jboss.netty.handler.ssl.SslHandler]) match {
    case null => false
    case _ => true
  }
  def remoteAddr =msg.context.getChannel.getRemoteAddress.asInstanceOf[java.net.InetSocketAddress].getAddress.getHostAddress

  def respond(rf: ResponseFunction[NHttpResponse]) =
    underlying.respond(rf)
}
/** Extension of basic request binding to expose Netty-specific attributes */
case class ReceivedMessage(
  request: NHttpRequest,
  context: ChannelHandlerContext,
  event: MessageEvent) {

  /** Binds a Netty HttpResponse res to Unfiltered's HttpResponse to apply any
   * response function to it. */
  def response[T <: NHttpResponse](res: T)(rf: ResponseFunction[T]) =
    rf(new ResponseBinding(res)).underlying

  /** @return a new Netty DefaultHttpResponse bound to an Unfiltered HttpResponse */
  val defaultResponse = response(new DefaultHttpResponse(HTTP_1_1, OK))_
  /** Applies rf to a new `defaultResponse` and writes it out */
  def respond: (ResponseFunction[NHttpResponse] => Unit) = {
    case Pass => context.sendUpstream(event)
    case rf =>
      val keepAlive = HttpHeaders.isKeepAlive(request)
      val closer = new unfiltered.response.Responder[NHttpResponse] {
        def respond(res: HttpResponse[NHttpResponse]) {
          res.outputStream.close()
          (
            if (keepAlive)
              unfiltered.response.Connection("Keep-Alive") ~>
              unfiltered.response.ContentLength(
                res.underlying.getContent().readableBytes().toString)
            else unfiltered.response.Connection("close")
          )(res)
        }
      }
      val future = event.getChannel.write(
        defaultResponse(rf ~> closer)
      )
      if (!keepAlive)
        future.addListener(ChannelFutureListener.CLOSE)
  }
}

class ResponseBinding[U <: NHttpResponse](res: U)
    extends HttpResponse(res) {
  private lazy val byteOutputStream = new ByteArrayOutputStream {
    override def close = {
      res.setContent(ChannelBuffers.copiedBuffer(this.toByteArray))
    }
  }

  def status(statusCode: Int) =
    res.setStatus(HttpResponseStatus.valueOf(statusCode))
  def header(name: String, value: String) = res.addHeader(name, value)

  def redirect(url: String) = {
    res.setStatus(HttpResponseStatus.FOUND)
    res.setHeader(HttpHeaders.Names.LOCATION, url)
  }

  def outputStream = byteOutputStream
}

private [netty] object URLParser {

  def urldecode(enc: String) : Map[String, Seq[String]] = {
    def decode(raw: String) = URLDecoder.decode(raw, HttpConfig.DEFAULT_CHARSET)
    val pairs = enc.split('&').flatMap {
      _.split('=') match {
        case Array(key, value) => List((decode(key), decode(value)))
        case Array(key) if key != "" => List((decode(key), ""))
        case _ => Nil
      }
    }.reverse
    (Map.empty[String, List[String]].withDefault {_ => Nil } /: pairs) {
      case (m, (k, v)) => m + (k -> (v :: m(k)))
    }
  }

}
