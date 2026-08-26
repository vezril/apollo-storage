# api-docs-portal

Apollo documents itself over its own HTTP port: an interactive Swagger UI page and the OpenAPI
document behind it, served offline from the classpath.

## ADDED Requirements

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
