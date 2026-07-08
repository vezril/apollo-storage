package apollostorage.api

import apollostorage.metrics.MetricsRegistry
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Instruments the gRPC surface at the HTTP/2 handler boundary (design D41): one decorator covers
 * every RPC — unary and streaming — without per-RPC code in `ObjectApiImpl`.
 *
 * The method label is the final segment of the request path
 * (`/apollostorage.grpc.ObjectApi/CreateBucket` → `CreateBucket`), a value bounded by the `.proto`.
 * Outcome is read from the `grpc-status` header when present — trailers-only error responses carry
 * it there (confirmed empirically) — otherwise it falls back to the HTTP status: a 2xx with no
 * `grpc-status` header is a success (status is then in the trailer) and is labelled `OK`.
 */
object GrpcMetrics:

  def instrument(
      inner: HttpRequest => Future[HttpResponse],
      metrics: MetricsRegistry
  )(using ec: ExecutionContext): HttpRequest => Future[HttpResponse] =
    request =>
      val method = methodLabel(request.uri.path.toString)
      val startNanos = System.nanoTime()
      def record(status: String): Unit =
        metrics.observeGrpc(method, status, (System.nanoTime() - startNanos) / 1e9)

      inner(request).transform {
        case Success(response) =>
          record(statusLabel(response))
          Success(response)
        case Failure(error) =>
          record("ERROR")
          Failure(error)
      }

  /** The RPC name = the last path segment; empty/odd paths fall back to "unknown". */
  private def methodLabel(path: String): String =
    path.split('/').filter(_.nonEmpty).lastOption.getOrElse("unknown")

  private def statusLabel(response: HttpResponse): String =
    response.headers.find(_.lowercaseName == "grpc-status").map(_.value) match
      case Some(code) => statusName(code)
      case None if response.status.isSuccess() => "OK"
      case None => response.status.intValue.toString

  /** Map a numeric grpc-status to its canonical name (e.g. 16 → UNAUTHENTICATED). */
  private def statusName(code: String): String =
    Try(io.grpc.Status.fromCodeValue(code.toInt).getCode.toString).toOption
      .filter(_ => code.forall(_.isDigit))
      .getOrElse(code)
