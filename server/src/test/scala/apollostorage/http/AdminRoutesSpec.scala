package apollostorage.http

import apollostorage.api.TokenAuthenticator
import apollostorage.blob.GcReport
import apollostorage.config.AuthConfig
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future

/**
 * Route coverage for the admin blob-gc trigger (design D56): dry-run vs. confirmed delete, the
 * report JSON, and the auth gate.
 */
final class AdminRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private def report(dryRun: Boolean) =
    GcReport(
      1,
      0,
      3,
      1,
      2,
      2048L,
      if dryRun then 0 else 2,
      if dryRun then 0L else 2048L,
      0,
      0,
      dryRun
    )

  // Capture the `delete` flag the route passed to the sweep.
  private def routeCapturing(auth: TokenAuthenticator, sink: Boolean => Unit): Route =
    AdminRoutes.blobGc(
      sweep = delete => { sink(delete); Future.successful(report(dryRun = !delete)) },
      authenticator = auth
    )

  private val open = new TokenAuthenticator(AuthConfig(enabled = false, tokens = Nil))

  "AdminRoutes.blobGc" should {

    "default to a dry run and return the report as JSON" in {
      var deleteFlag = true
      Post("/admin/blob-gc") ~> routeCapturing(open, deleteFlag = _) ~> check {
        status shouldBe StatusCodes.OK
        deleteFlag shouldBe false // dry-run by default
        responseAs[String] should include(""""dryRun":true""")
        responseAs[String] should include(""""orphansFound":2""")
      }
    }

    "run a real delete only when ?delete=true" in {
      var deleteFlag = false
      Post("/admin/blob-gc?delete=true") ~> routeCapturing(open, deleteFlag = _) ~> check {
        status shouldBe StatusCodes.OK
        deleteFlag shouldBe true
        responseAs[String] should include(""""dryRun":false""")
      }
    }

    "require a valid bearer token when auth is enabled" in {
      val secured = new TokenAuthenticator(AuthConfig(enabled = true, tokens = Seq("s3cret")))
      val route = routeCapturing(secured, _ => ())
      // no token -> 401
      Post("/admin/blob-gc") ~> route ~> check(status shouldBe StatusCodes.Unauthorized)
      // valid token -> 200
      Post("/admin/blob-gc").withHeaders(RawHeader("authorization", "Bearer s3cret")) ~> route ~>
        check(status shouldBe StatusCodes.OK)
    }
  }
