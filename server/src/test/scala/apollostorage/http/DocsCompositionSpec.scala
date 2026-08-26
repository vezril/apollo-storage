package apollostorage.http

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Route composition: adding the docs portal must not shadow anything already mounted on the HTTP
 * listener. Mirrors how `Main` folds the routes together (rest :: docs :: metrics ++ admin over
 * health), inside the tracing directive.
 */
final class DocsCompositionSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  /** Stand-ins for the object API and admin surface — only their path shapes matter here. */
  private val restLike: Route = (pathPrefix("v1" / "buckets") & get)(complete("buckets"))
  private val adminLike: Route = (path("admin" / "blob-gc") & post)(complete("swept"))

  private val composed: Route = RequestTracing.withCorrelationId {
    List(restLike, DocsRoutes(), adminLike)
      .foldLeft(HealthRoutes("9.9.9", () => true))(_ ~ _)
  }

  "the composed HTTP route tree" should {

    "still serve /health" in {
      Get("/health") ~> composed ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"version\":\"9.9.9\"")
      }
    }

    "serve the docs portal" in {
      Get("/docs") ~> composed ~> check(status shouldBe StatusCodes.OK)
      Get("/docs/openapi.yaml") ~> composed ~> check(status shouldBe StatusCodes.OK)
    }

    "leave the object API and admin routes reachable" in {
      Get("/v1/buckets") ~> composed ~> check(responseAs[String] shouldBe "buckets")
      Post("/admin/blob-gc") ~> composed ~> check(responseAs[String] shouldBe "swept")
    }

    "keep echoing the correlation id on a docs request" in {
      Get("/docs") ~> composed ~> check {
        header("X-Correlation-Id") shouldBe defined
      }
    }
  }
