package apollostorage.http

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path, Paths}
import scala.annotation.tailrec

/**
 * Packaging guarantees behind the documentation portal (design D1/D2). Two things must hold for the
 * docs to work offline and to stay honest: Swagger UI's assets resolve from **our** classpath
 * (never a CDN), and the OpenAPI document we serve is the very file reviewed in the repo — copied
 * in at build time rather than kept as a second copy that can drift.
 */
final class DocsPackagingSpec extends AnyWordSpec with Matchers:

  private def resourceBytes(path: String): Option[Array[Byte]] =
    Option(getClass.getClassLoader.getResourceAsStream(path)).map { in =>
      try in.readAllBytes()
      finally in.close()
    }

  /** Tests fork with the module as cwd, so walk up to the repo root that owns `docs/`. */
  private lazy val repoRoot: Path =
    @tailrec def up(dir: Path): Path =
      if Files.exists(dir.resolve("docs/rest-api.openapi.yaml")) then dir
      else
        val parent = dir.getParent
        if parent == null then
          fail("could not locate the repository root (no docs/rest-api.openapi.yaml above cwd)")
        else up(parent)
    up(Paths.get("").toAbsolutePath)

  "the Swagger UI webjar" should {

    "resolve every asset the docs page needs, at the pinned version path" in {
      DocsAssets.UiAssets.foreach { asset =>
        val path = DocsAssets.webjarResource(asset)
        withClue(
          s"classpath resource '$path' is missing — if the swagger-ui webjar version moved, " +
            s"update DocsAssets.SwaggerUiVersion so the served page does not silently break: "
        ) {
          resourceBytes(path) shouldBe defined
        }
      }
    }

    "serve non-empty asset payloads" in {
      DocsAssets.UiAssets.foreach { asset =>
        resourceBytes(DocsAssets.webjarResource(asset)).map(_.length).getOrElse(0) should be > 0
      }
    }
  }

  "the packaged OpenAPI document" should {

    "be present on the classpath" in {
      resourceBytes(DocsAssets.SpecResource) shouldBe defined
    }

    "be byte-identical to docs/rest-api.openapi.yaml" in {
      val packaged = resourceBytes(DocsAssets.SpecResource).getOrElse(
        fail(s"'${DocsAssets.SpecResource}' is not on the classpath")
      )
      val onDisk = Files.readAllBytes(repoRoot.resolve("docs/rest-api.openapi.yaml"))
      withClue(
        "the packaged spec drifted from the reviewed one — it must be copied at build time: "
      ) {
        packaged.toSeq shouldBe onDisk.toSeq
      }
    }
  }
