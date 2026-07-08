package apollostorage.http

import apollostorage.metrics.MetricsRegistry
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Route coverage for the `/metrics` endpoint (design D40/D44): exposition content type, no-auth
 * access, and the disabled path (route absent ⇒ 404).
 */
final class MetricsRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  "MetricsRoutes" should {

    "serve exposition text on GET /metrics" in {
      val m = new MetricsRegistry("9.9.9", () => true)
      Get("/metrics") ~> MetricsRoutes(m) ~> check {
        status shouldBe StatusCodes.OK
        contentType.toString should startWith("text/plain; version=0.0.4")
        responseAs[String] should include("apollostorage_build_info")
        responseAs[String] should include("apollostorage_ready 1.0")
      }
    }

    "require no authentication" in {
      val m = new MetricsRegistry("v", () => true)
      // An authorization header is neither required nor consulted.
      Get("/metrics").withHeaders(RawHeader("authorization", "Bearer irrelevant")) ~>
        MetricsRoutes(m) ~> check {
          status shouldBe StatusCodes.OK
        }
    }

    "return 404 for /metrics when the route is absent (metrics disabled)" in {
      // When disabled, Main constructs no registry and adds no route; a health-only
      // surface falls through to 404 for /metrics.
      Get("/metrics") ~> Route.seal(HealthRoutes("v", () => true)) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }
