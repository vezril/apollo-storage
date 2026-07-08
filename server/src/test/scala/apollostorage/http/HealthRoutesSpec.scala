package apollostorage.http

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class HealthRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private def routes(ready: Boolean): Route = HealthRoutes("1.2.3", () => ready)

  "GET /health" should {

    "return 200 UP with the build version when ready" in {
      Get("/health") ~> routes(ready = true) ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"status\":\"UP\"")
        body should include("\"service\":\"apollostorage\"")
        body should include("\"version\":\"1.2.3\"")
      }
    }

    "return 503 DOWN once readiness is withdrawn" in {
      Get("/health") ~> routes(ready = false) ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
        responseAs[String] should include("\"status\":\"DOWN\"")
      }
    }

    "return 404 for an unknown route while /health stays healthy" in {
      Get("/nope") ~> Route.seal(routes(ready = true)) ~> check {
        status shouldBe StatusCodes.NotFound
      }
      // subsequent /health still works
      Get("/health") ~> routes(ready = true) ~> check {
        status shouldBe StatusCodes.OK
      }
    }
  }
