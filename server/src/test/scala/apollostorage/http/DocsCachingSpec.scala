package apollostorage.http

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.{
  `Cache-Control`,
  ETag,
  EntityTag,
  `If-None-Match`
}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Cache validation for the documentation portal (add-http-caching §4). `/docs` re-sent ~1.5 MB of
 * Swagger UI JavaScript on every load; with a validator a repeat visit transfers nothing. The
 * assets are served at version-less URLs, so they are revalidated — never frozen — or a viewer
 * would be pinned to a stale UI after an upgrade (design D5).
 */
final class DocsCachingSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val routes: Route = DocsRoutes()

  private def etagOf(path: String): EntityTag =
    Get(path) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      header[ETag].getOrElse(fail(s"no ETag on $path")).etag
    }

  "Documentation assets" should {

    "carry a validator" in {
      DocsAssets.UiAssets.foreach { asset =>
        withClue(s"asset $asset: ") {
          etagOf(s"/docs/$asset").tag should not be empty
        }
      }
    }

    "give different assets different validators" in {
      // Pekko derives the validator from the resource itself (length + timestamp), so distinct
      // assets cannot collide and a changed asset invalidates a client's copy. See design D5 for
      // why this is preferred over hand-rolling a version-derived tag.
      val tags = DocsAssets.UiAssets.map(a => etagOf(s"/docs/$a").tag)
      tags.distinct.size shouldBe tags.size
    }

    "answer 304 with no body when the asset validator matches" in {
      DocsAssets.UiAssets.foreach { asset =>
        val tag = etagOf(s"/docs/$asset")
        Get(s"/docs/$asset").withHeaders(`If-None-Match`(tag)) ~> routes ~> check {
          withClue(s"asset $asset: ") {
            status shouldBe StatusCodes.NotModified
            responseAs[String] shouldBe ""
          }
        }
      }
    }

    "serve the asset when the validator is stale" in {
      val asset = DocsAssets.UiAssets.head
      Get(s"/docs/$asset").withHeaders(`If-None-Match`(EntityTag("stale-from-an-old-version"))) ~>
        routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[String].length should be > 0
        }
    }
  }

  "The OpenAPI document" should {

    "carry a validator derived from its content" in {
      etagOf("/docs/openapi.yaml").tag should not be empty
    }

    "answer 304 when the validator matches" in {
      val tag = etagOf("/docs/openapi.yaml")
      Get("/docs/openapi.yaml").withHeaders(`If-None-Match`(tag)) ~> routes ~> check {
        status shouldBe StatusCodes.NotModified
        responseAs[String] shouldBe ""
      }
    }
  }

  "Documentation cache directives" should {

    "require revalidation rather than freezing, on assets and on the page" in {
      val paths =
        "/docs" :: "/docs/openapi.yaml" :: DocsAssets.UiAssets.map(a => s"/docs/$a").toList
      paths.foreach { path =>
        Get(path) ~> routes ~> check {
          val rendered = header[`Cache-Control`].map(_.value.toLowerCase).getOrElse("")
          withClue(s"$path directives were '$rendered': ") {
            rendered should include("no-cache")
            rendered should not include "immutable"
            rendered should not include "max-age"
          }
        }
      }
    }
  }
