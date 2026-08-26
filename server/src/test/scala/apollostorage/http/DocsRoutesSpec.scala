package apollostorage.http

import org.apache.pekko.http.scaladsl.model.{ContentTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.yaml.snakeyaml.Yaml

import java.util.Map as JMap
import scala.jdk.CollectionConverters.*

/**
 * The documentation portal (api-docs-portal spec). It must render from the service itself with no
 * outbound request, serve the reviewed OpenAPI document, stay readable without a token, and refuse
 * anything but safe methods.
 */
final class DocsRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val routes: Route = DocsRoutes()

  "GET /docs" should {

    "return the documentation page as HTML" in {
      Get("/docs") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`text/html(UTF-8)`
        responseAs[String] should include("swagger-ui")
      }
    }

    "reference the OpenAPI document and every UI asset it needs" in {
      Get("/docs") ~> routes ~> check {
        val body = responseAs[String]
        body should include("/docs/openapi.yaml")
        DocsAssets.UiAssets.foreach(asset => body should include(s"/docs/$asset"))
      }
    }

    "resolve with a trailing slash too" in {
      Get("/docs/") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`text/html(UTF-8)`
      }
    }

    "reference no external host, so the page renders offline" in {
      Get("/docs") ~> routes ~> check {
        val body = responseAs[String]
        withClue(s"the page must not load anything from a third party:\n$body\n") {
          body should not include "http://"
          body should not include "https://"
          body should not include "//cdn"
          body should not include "unpkg"
          body should not include "jsdelivr"
        }
      }
    }
  }

  "GET /docs/openapi.yaml" should {

    "serve a document that parses as OpenAPI with paths" in {
      Get("/docs/openapi.yaml") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val parsed = new Yaml().load[JMap[String, Object]](responseAs[String]).asScala
        parsed.get("openapi").map(_.toString) shouldBe Some("3.0.3")
        val paths = parsed("paths").asInstanceOf[JMap[String, Object]].asScala
        paths.keySet should contain allOf ("/health", "/v1/buckets", "/docs")
      }
    }
  }

  "the documentation endpoints" should {

    "serve the UI assets from the service itself" in {
      DocsAssets.UiAssets.foreach { asset =>
        Get(s"/docs/$asset") ~> routes ~> check {
          withClue(s"asset $asset: ") {
            status shouldBe StatusCodes.OK
            responseAs[String].length should be > 0
          }
        }
      }
    }

    "answer without an Authorization header (they carry no data, only shape)" in {
      // The route takes no authenticator at all — unauthenticated by construction, not by config.
      Get("/docs") ~> routes ~> check(status shouldBe StatusCodes.OK)
      Get("/docs/openapi.yaml") ~> routes ~> check(status shouldBe StatusCodes.OK)
    }

    "reject unsafe methods" in {
      Post("/docs/openapi.yaml") ~> routes ~> check(handled shouldBe false)
      Put("/docs") ~> routes ~> check(handled shouldBe false)
      Delete("/docs/openapi.yaml") ~> routes ~> check(handled shouldBe false)
    }

    "not serve arbitrary classpath resources" in {
      Get("/docs/application.conf") ~> routes ~> check(handled shouldBe false)
      Get("/docs/logback.xml") ~> routes ~> check(handled shouldBe false)
    }
  }
