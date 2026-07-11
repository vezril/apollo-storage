package apollostorage.http

import apollostorage.tracing.CorrelationId
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Route coverage for the HTTP tracing wrapper (request-tracing capability): the `X-Correlation-Id`
 * header is returned on a normal completion, on an explicit error status, and on a 404 for an
 * unmatched path (the inner route is sealed below the response mapping).
 */
final class RequestTracingSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private def cid: Option[String] =
    header(CorrelationId.HttpHeader).map(_.value)

  "RequestTracing.withCorrelationId" should {

    "return X-Correlation-Id on a successful response" in {
      val route = RequestTracing.withCorrelationId(path("ok")(get(complete(StatusCodes.OK))))
      Get("/ok") ~> route ~> check {
        status shouldBe StatusCodes.OK
        cid.map(_.length) shouldBe Some(12)
      }
    }

    "return X-Correlation-Id on an error response" in {
      val route =
        RequestTracing.withCorrelationId(path("boom")(complete(StatusCodes.InternalServerError)))
      Get("/boom") ~> route ~> check {
        status shouldBe StatusCodes.InternalServerError
        cid should not be empty
      }
    }

    "return X-Correlation-Id on a 404 for an unmatched path" in {
      val route = RequestTracing.withCorrelationId(path("known")(complete(StatusCodes.OK)))
      Get("/unknown") ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        cid should not be empty
      }
    }
  }
