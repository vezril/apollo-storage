package apollostorage.http

import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.{`Cache-Control`, CacheDirectives}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

/**
 * The documentation portal (api-docs-portal spec): Swagger UI at `GET /docs`, the OpenAPI document
 * at `GET /docs/openapi.yaml`, and the UI's own assets beneath the same prefix.
 *
 * Three properties are deliberate:
 *   - **Offline.** Every asset is served from this application's classpath (design D1). The page
 *     references nothing outside this origin, so it renders on a LAN with no internet egress —
 *     which is exactly when someone is debugging the cluster and needs it.
 *   - **Unauthenticated by construction.** This route takes no authenticator, so there is no
 *     configuration in which the docs demand a token. They expose the API's shape — the same
 *     information as the public repository — never data or credentials (design D3).
 *   - **A closed asset set.** Only the names in [[DocsAssets.UiAssets]] are served, so the route
 *     cannot be walked into the rest of the classpath (e.g. `application.conf`).
 */
object DocsRoutes:

  private val Yaml: ContentType =
    ContentType.parse("application/yaml").getOrElse(ContentTypes.`text/plain(UTF-8)`)

  private def contentTypeOf(asset: String): ContentType =
    if asset.endsWith(".css") then ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`)
    else if asset.endsWith(".js") then
      ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`)
    else ContentTypes.`application/octet-stream`

  /**
   * Documentation is revalidated, never frozen. The assets are served at version-less URLs
   * (`/docs/swagger-ui.css`) while their bytes come from a version-pinned classpath path, so a
   * positive `max-age` would pin a viewer to a stale UI across an upgrade. `no-cache` keeps the
   * copy but re-checks it, turning a repeat `/docs` load from ~1.5 MB of JavaScript into a `304`
   * with an empty body. `public` is safe here: these endpoints expose only the API's shape.
   *
   * The validators themselves come from pekko's resource directives, which already derive an `ETag`
   * from the resource and answer conditional requests — see the design note on D5.
   */
  private val docsCacheControl: HttpHeader =
    `Cache-Control`(CacheDirectives.public, CacheDirectives.`no-cache`)

  def apply(): Route =
    (pathPrefix("docs") & get & respondWithHeader(docsCacheControl)) {
      concat(
        pathEndOrSingleSlash {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, page))
        },
        path("openapi.yaml") {
          getFromResource(DocsAssets.SpecResource, Yaml)
        },
        // Whitelisted assets only — never an arbitrary classpath path.
        path(Segment) { asset =>
          if DocsAssets.UiAssets.contains(asset) then
            getFromResource(DocsAssets.webjarResource(asset), contentTypeOf(asset))
          else reject
        }
      )
    }

  /**
   * The Swagger UI bootstrap page. Written here rather than taken from the webjar's own
   * `index.html` so the asset references stay under our control (and provably local).
   *
   * The document's first `servers` entry is relative, so "try it out" targets whichever deployment
   * served this page rather than a hard-coded host.
   */
  private val page: String =
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  <title>ApolloStorage API</title>
       |  <link rel="stylesheet" href="/docs/swagger-ui.css">
       |  <style>
       |    body { margin: 0; background: #fafafa; }
       |    .apollo-note {
       |      font: 13px/1.5 system-ui, sans-serif; color: #3b4151;
       |      padding: 10px 20px; background: #eef2f7; border-bottom: 1px solid #d5dde7;
       |    }
       |    .apollo-note code { background: #dde5ee; padding: 1px 4px; border-radius: 3px; }
       |  </style>
       |</head>
       |<body>
       |  <p class="apollo-note">
       |    HTTP API. Requests sent with <em>Try it out</em> go to the server selected below and are
       |    real &mdash; <code>DELETE</code> included. The gRPC object API is a separate contract,
       |    defined by the protobuf in the-lexicon.
       |  </p>
       |  <div id="swagger-ui"></div>
       |  <script src="/docs/swagger-ui-bundle.js"></script>
       |  <script>
       |    window.ui = SwaggerUIBundle({
       |      url: '/docs/openapi.yaml',
       |      dom_id: '#swagger-ui',
       |      deepLinking: true,
       |      presets: [SwaggerUIBundle.presets.apis]
       |    });
       |  </script>
       |</body>
       |</html>
       |""".stripMargin
