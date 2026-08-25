package apollostorage.http

import apollostorage.tracing.CorrelationId
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.{LoggerFactory, MDC}

/**
 * Correlation + access logging for the HTTP route tree (request-tracing capability), the HTTP
 * counterpart of [[apollostorage.api.GrpcTracing]]. Wrapping the routes with [[withCorrelationId]]
 * mints an id, puts it in the MDC for the request's logs, access-logs entry and completion, and
 * adds the `X-Correlation-Id` response header on every response. The inner routes are sealed below
 * the response mapping so that rejections and exceptions are turned into responses first — hence
 * the header is present on 4xx/5xx and 404s too, not only on explicit completions.
 */
object RequestTracing:

  private val log = LoggerFactory.getLogger("apollostorage.http.access")

  def withCorrelationId(inner: Route): Route =
    extractRequest { request =>
      val id = CorrelationId.mint()
      val method = request.method.value
      val path = request.uri.path.toString
      withMdc(id)(log.info(s"→ HTTP $method $path"))
      mapResponse { response =>
        withMdc(id)(log.info(s"← HTTP $method $path ${response.status.intValue}"))
        response.addHeader(RawHeader(CorrelationId.HttpHeader, id))
      } {
        Route.seal(inner)
      }
    }

  private def withMdc[A](id: String)(body: => A): A =
    MDC.put(CorrelationId.MdcKey, id)
    try body
    finally MDC.remove(CorrelationId.MdcKey)
