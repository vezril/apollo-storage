package apollostorage.http

/**
 * Packaging constants for the documentation portal (design D1/D2).
 *
 * The Swagger UI webjar embeds its version in the resource path, which makes a version bump able to
 * break the page silently. Rather than take another dependency to look the path up at runtime
 * (`webjars-locator` and its classpath scanning), the version lives here as one constant and
 * `DocsPackagingSpec` asserts the assets actually resolve — so a bad bump fails a test instead of
 * shipping a blank page.
 */
object DocsAssets:

  /** Pinned swagger-ui webjar version; must match the dependency in build.sbt. */
  val SwaggerUiVersion: String = "5.25.3"

  /** Classpath root the webjar unpacks to. */
  val WebjarRoot: String = s"META-INF/resources/webjars/swagger-ui/$SwaggerUiVersion"

  /**
   * The assets the documentation page loads. Deliberately a closed set: the asset route serves only
   * these names, so it can never be walked into the rest of the classpath.
   */
  val UiAssets: Seq[String] = Seq("swagger-ui.css", "swagger-ui-bundle.js")

  /** The OpenAPI document, copied into managed resources from `docs/` at build time. */
  val SpecResource: String = "openapi/rest-api.openapi.yaml"

  /** Classpath location of one webjar asset. */
  def webjarResource(asset: String): String = s"$WebjarRoot/$asset"
