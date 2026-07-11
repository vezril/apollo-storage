package apollostorage.api

import apollostorage.tracing.CorrelationId
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.slf4j.{LoggerFactory, MDC}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Correlation + access logging at the gRPC HTTP/2 handler boundary (request-tracing capability), a
 * sibling of [[GrpcMetrics]]. One decorator covers every RPC: it mints a correlation id, stamps it
 * into the REQUEST metadata (so `ObjectApiImpl.guarded` can re-establish it in the MDC for the
 * handler's own async logs — a value set here does not survive Pekko gRPC's internal dispatch),
 * access-logs the exchange, and echoes the id on the RESPONSE as `x-correlation-id` for both
 * success and trailers-only error responses (where a gRPC error still arrives as an
 * `HttpResponse`).
 */
object GrpcTracing:

  private val log = LoggerFactory.getLogger("apollostorage.api.access")

  def instrument(
      inner: HttpRequest => Future[HttpResponse]
  )(using ec: ExecutionContext): HttpRequest => Future[HttpResponse] =
    request =>
      val id = CorrelationId.mint()
      val method = methodLabel(request.uri.path.toString)
      val tagged = request.addHeader(RawHeader(CorrelationId.MetadataKey, id))
      withMdc(id)(log.info(s"→ gRPC $method"))
      val startNanos = System.nanoTime()
      inner(tagged).transform {
        case Success(response) =>
          logDone(id, method, statusLabel(response), startNanos)
          Success(response.addHeader(RawHeader(CorrelationId.MetadataKey, id)))
        case Failure(error) =>
          logDone(id, method, "ERROR", startNanos)
          Failure(error)
      }

  private def logDone(id: String, method: String, status: String, startNanos: Long): Unit =
    val millis = (System.nanoTime() - startNanos) / 1000000L
    withMdc(id)(log.info(s"← gRPC $method $status ${millis}ms"))

  /**
   * Run `body` with the id in MDC, then restore — so an async completion log is still correlated.
   */
  private def withMdc[A](id: String)(body: => A): A =
    MDC.put(CorrelationId.MdcKey, id)
    try body
    finally MDC.remove(CorrelationId.MdcKey)

  /** The RPC name = the last path segment; empty/odd paths fall back to "unknown". */
  private def methodLabel(path: String): String =
    path.split('/').filter(_.nonEmpty).lastOption.getOrElse("unknown")

  /** gRPC status from the `grpc-status` header when present, else the HTTP status (200 ⇒ OK). */
  private def statusLabel(response: HttpResponse): String =
    response.headers.find(_.lowercaseName == "grpc-status").map(_.value) match
      case Some(code) => code
      case None if response.status.isSuccess() => "OK"
      case None => response.status.intValue.toString
