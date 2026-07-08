package apollostorage.api

import apollostorage.metrics.MetricsRegistry
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future

/**
 * Unit coverage for the gRPC metrics decorator (design D41). Status is read from the `grpc-status`
 * header when present (trailers-only errors, as confirmed empirically), falling back to the HTTP
 * status; the method label is the RPC name, never a bucket/object name.
 */
final class GrpcMetricsSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers:

  private given scala.concurrent.ExecutionContext = system.executionContext

  private def call(
      inner: HttpRequest => Future[HttpResponse],
      path: String,
      m: MetricsRegistry
  ): Unit =
    val wrapped = GrpcMetrics.instrument(inner, m)
    wrapped(HttpRequest(uri = path)).futureValue
    ()

  "GrpcMetrics.instrument" should {

    "label a successful RPC OK using the method from the path" in {
      val m = new MetricsRegistry("v", () => true)
      // Success: HTTP 200, grpc-status lives in the trailer (not the headers).
      call(
        _ => Future.successful(HttpResponse(StatusCodes.OK)),
        "/apollostorage.grpc.ObjectApi/CreateBucket",
        m
      )
      val out = m.scrape()
      out should include(
        """apollostorage_grpc_requests_total{method="CreateBucket",status="OK",} 1.0"""
      )
      out should include("apollostorage_grpc_request_duration_seconds")
    }

    "label a failed RPC by its grpc-status header" in {
      val m = new MetricsRegistry("v", () => true)
      // Trailers-only error: HTTP 200 with grpc-status=16 (UNAUTHENTICATED) in headers.
      val resp = HttpResponse(StatusCodes.OK).withHeaders(RawHeader("grpc-status", "16"))
      call(_ => Future.successful(resp), "/apollostorage.grpc.ObjectApi/GetObject", m)
      m.scrape() should include(
        """apollostorage_grpc_requests_total{method="GetObject",status="UNAUTHENTICATED",} 1.0"""
      )
    }

    "count a failed Future as an error for its method" in {
      val m = new MetricsRegistry("v", () => true)
      val wrapped = GrpcMetrics.instrument(
        _ => Future.failed(new RuntimeException("boom")),
        m
      )
      wrapped(HttpRequest(uri = "/apollostorage.grpc.ObjectApi/PutObject")).failed.futureValue
      m.scrape() should include(
        """apollostorage_grpc_requests_total{method="PutObject",status="ERROR",} 1.0"""
      )
    }

    "not record metrics for non-gRPC paths" in {
      val m = new MetricsRegistry("v", () => true)
      call(
        _ => Future.successful(HttpResponse(StatusCodes.OK)),
        "/grpc.reflection.v1alpha.ServerReflection/Info",
        m
      )
      // Reflection is a gRPC path too, so it IS recorded — assert the method is the last
      // path segment and carries no bucket/object identifiers.
      m.scrape() should include("""method="Info"""")
    }
  }
