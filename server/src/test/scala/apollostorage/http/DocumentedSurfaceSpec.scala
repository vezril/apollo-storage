package apollostorage.http

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.yaml.snakeyaml.Yaml

import java.util.Map as JMap
import scala.jdk.CollectionConverters.*

/**
 * The documented surface must not be a strict subset of the served one (api-docs-portal spec,
 * design D4). This is the guard that keeps the OpenAPI document from quietly rotting the way it
 * already did once — it shipped describing only the `/v1/buckets…` paths while the service also
 * served health, metrics and blob-gc.
 *
 * The inventory below is maintained by hand on purpose. Pekko-http routes are opaque functions and
 * cannot be enumerated reliably, so there is no honest way to reflect over the live route tree. The
 * residual risk is therefore explicit: **adding a route obliges you to add it here**, and this test
 * then forces you to document it. That obligation is also recorded in `AGENTS.md`.
 */
final class DocumentedSurfaceSpec extends AnyWordSpec with Matchers:

  /** Every public HTTP path the service can serve. Extend when you mount a new route. */
  private val PublicHttpSurface: Seq[String] = Seq(
    "/health",
    "/metrics",
    "/admin/blob-gc",
    "/docs",
    "/docs/openapi.yaml",
    "/v1/buckets",
    "/v1/buckets/{bucket}",
    "/v1/buckets/{bucket}/objects",
    "/v1/buckets/{bucket}/objects/{object}"
  )

  private lazy val document: collection.mutable.Map[String, Object] =
    val in = Option(getClass.getClassLoader.getResourceAsStream(DocsAssets.SpecResource))
      .getOrElse(fail(s"'${DocsAssets.SpecResource}' is not on the classpath"))
    try new Yaml().load[JMap[String, Object]](in).asScala
    finally in.close()

  private lazy val paths: collection.mutable.Map[String, Object] =
    document("paths").asInstanceOf[JMap[String, Object]].asScala

  private def operation(path: String, method: String): collection.mutable.Map[String, Object] =
    paths
      .getOrElse(path, fail(s"'$path' is not documented"))
      .asInstanceOf[JMap[String, Object]]
      .asScala
      .getOrElse(method, fail(s"'$method $path' is not documented"))
      .asInstanceOf[JMap[String, Object]]
      .asScala

  "the OpenAPI document" should {

    "document every public HTTP path the service serves" in {
      val undocumented = PublicHttpSurface.filterNot(paths.contains)
      withClue(
        "these routes are served but undocumented — add them to docs/rest-api.openapi.yaml: "
      ) {
        undocumented shouldBe empty
      }
    }

    "not document paths the service does not serve" in {
      val phantom = paths.keys.filterNot(PublicHttpSurface.contains).toSeq
      withClue("documented but not served (or missing from this test's inventory): ") {
        phantom shouldBe empty
      }
    }
  }

  "documented auth expectations" should {

    "leave mutating object operations under the default bearer requirement" in {
      // The document declares `security: [bearerAuth]` globally; a mutation must not opt out of it.
      Seq(
        ("/v1/buckets/{bucket}", "put"),
        ("/v1/buckets/{bucket}", "delete"),
        ("/v1/buckets/{bucket}/objects/{object}", "put"),
        ("/v1/buckets/{bucket}/objects/{object}", "delete")
      ).foreach { case (path, method) =>
        withClue(s"$method $path must not declare its own empty security: ") {
          operation(path, method).get("security") shouldBe None
        }
      }
      document.get("security") shouldBe defined
    }

    "mark the unauthenticated endpoints as requiring no token" in {
      Seq("/health", "/metrics", "/docs", "/docs/openapi.yaml").foreach { path =>
        withClue(s"GET $path must declare an empty security list: ") {
          operation(path, "get").get("security") shouldBe Some(java.util.Collections.emptyList())
        }
      }
    }
  }
