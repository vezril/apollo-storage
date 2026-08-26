# api-docs-portal Specification

## Purpose

Apollo documents itself over its own HTTP port: an interactive Swagger UI page and the OpenAPI
document behind it, served offline from the classpath so the documentation renders on a LAN with no
internet egress. The document served is the same artifact reviewed in the repository, and it must
describe every HTTP endpoint the service exposes — documentation that lags the service is the
failure this capability exists to prevent.
## Requirements
### Requirement: Interactive API documentation page

The service SHALL serve an interactive API documentation page at `GET /docs` on the HTTP listener,
rendering Swagger UI against the service's own OpenAPI document.

#### Scenario: The docs page loads

- **WHEN** `GET /docs` is requested
- **THEN** the response is `200` with content type `text/html`, and the body references the OpenAPI
  document path and the Swagger UI assets the page needs to render

#### Scenario: Edge case — trailing slash resolves to the same page

- **WHEN** `GET /docs/` is requested
- **THEN** the response is the documentation page, not a `404`

### Requirement: OpenAPI document served over HTTP

The service SHALL serve its OpenAPI document at `GET /docs/openapi.yaml`. The document served SHALL be
the same artifact kept at `docs/rest-api.openapi.yaml` in the repository — packaged at build time, never
maintained as a second copy — so the served contract cannot drift from the reviewed one.

#### Scenario: The document is served and parses

- **WHEN** `GET /docs/openapi.yaml` is requested
- **THEN** the response is `200`, the body is the OpenAPI document, and it parses as YAML declaring an
  `openapi` version and a `paths` object

#### Scenario: The served document matches the repository artifact

- **GIVEN** the packaged resource and `docs/rest-api.openapi.yaml`
- **WHEN** both are read
- **THEN** their contents are identical

### Requirement: Documentation assets served offline

Swagger UI's static assets SHALL be served from the application's own classpath, and the documentation
page SHALL NOT reference any external host. Apollo runs on a LAN without assumed internet egress, so
documentation MUST render with no outbound network request.

#### Scenario: Assets resolve from the service itself

- **WHEN** the documentation page requests its stylesheet and script assets
- **THEN** each is served by the service with a `200` and a matching content type

#### Scenario: No external references

- **WHEN** the documentation page body is inspected
- **THEN** it contains no absolute URL to a third-party host (no CDN reference)

### Requirement: Documented HTTP surface is complete

The served OpenAPI document SHALL describe every HTTP endpoint the service exposes to callers —
including `/health`, `/metrics`, the `/v1/buckets…` object API, and, when blob GC is enabled,
`/admin/blob-gc` — so the documentation cannot describe a strict subset of the running service.

#### Scenario: Every public HTTP route appears in the document

- **GIVEN** the OpenAPI document
- **WHEN** its `paths` are compared against the service's mounted HTTP routes
- **THEN** every mounted public route has a corresponding documented path

#### Scenario: Documented operations carry their auth expectations

- **GIVEN** the OpenAPI document
- **WHEN** a mutating object operation is inspected
- **THEN** it declares the bearer security scheme, and the unauthenticated endpoints (`/health`,
  `/docs`) declare no security requirement

### Requirement: Documentation endpoints are read-only and unauthenticated

The documentation endpoints SHALL be readable without a bearer token, and SHALL expose only the shape
of the API — never stored objects, credentials, or configuration values. They SHALL accept only safe
methods.

#### Scenario: Readable with auth enabled

- **GIVEN** the service is running with authentication enabled
- **WHEN** `GET /docs` and `GET /docs/openapi.yaml` are requested with no `Authorization` header
- **THEN** both return `200`

#### Scenario: Edge case — unsafe methods are rejected

- **WHEN** `POST /docs/openapi.yaml` is requested
- **THEN** the request is rejected, and no documentation resource is modified

### Requirement: Documentation assets are revalidated rather than re-sent

The documentation page's static assets and the served OpenAPI document SHALL carry a cache validator
and SHALL be revalidated rather than re-transferred, so that repeat visits to `/docs` do not re-send
the full asset payload.

The assets are served at paths that do not carry the packaged library's version, so they SHALL NOT be
frozen with a positive freshness lifetime — a viewer must never be pinned to a stale documentation UI
after an upgrade. A validator SHALL change when the bytes it identifies change, so that upgrading the
packaged UI or editing the OpenAPI document invalidates a client's copy.

#### Scenario: Assets carry a validator

- **WHEN** a documentation asset is requested
- **THEN** the response is `200` and carries an `ETag`

#### Scenario: A repeat visit transfers no asset payload

- **GIVEN** a client holding the current validator for an asset
- **WHEN** it re-requests that asset with `If-None-Match`
- **THEN** the response is `304` with no body

#### Scenario: The OpenAPI document is validated too

- **GIVEN** a client holding the current validator for `/docs/openapi.yaml`
- **WHEN** it re-requests the document with `If-None-Match`
- **THEN** the response is `304` with no body

#### Scenario: Different assets carry different validators

- **WHEN** two different documentation assets are requested
- **THEN** their validators differ, so a client cannot confuse one for the other

#### Scenario: A stale validator is served fresh content

- **GIVEN** a client presenting a validator that does not match the current asset
- **WHEN** it requests that asset
- **THEN** the response is `200` with the asset's current bytes and its current validator

#### Scenario: Edge case — the documentation page itself stays revalidated

- **WHEN** the documentation page at `/docs` is requested
- **THEN** its cache directives require revalidation before reuse, so a change to the page is picked
  up on the next visit rather than after an expiry window

