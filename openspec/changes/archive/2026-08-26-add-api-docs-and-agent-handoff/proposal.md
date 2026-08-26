## Why

Apollo's HTTP surface has outgrown its documentation. The OpenAPI spec added with the REST API
documents only the four `/v1/buckets…` paths — `/health`, `/metrics`, and `/admin/blob-gc` are
undocumented — and the spec is a YAML file in the repo: to try a call you must read it, hand-build a
request, and know the shape. There is no way to *see* or *exercise* the API from a browser. The
Insomnia collection is worse: it predates the REST API entirely, so it covers only health/metrics/admin
plus gRPC, and hands a new user a collection that silently omits the object API.

Separately, every session that picks this project up (usually an LLM, usually Claude) re-derives the
same context — the event-sourced/CQRS shape, the blob-ref scheme, the TDD-and-OpenSpec workflow, what
is deployed where. That rediscovery is repeated, lossy, and occasionally wrong. A maintained handoff
file makes the project self-describing to its most common contributor.

## What Changes

- **Interactive API docs served by Apollo**: a new `GET /docs` route renders Swagger UI against
  Apollo's own OpenAPI document, and `GET /docs/openapi.yaml` serves that document. Swagger UI assets
  are served **from the classpath** (a webjar), never a CDN — Apollo is a self-hosted LAN service and
  must document itself with no internet egress.
- **The OpenAPI document becomes complete**: `/health`, `/metrics`, and `/admin/blob-gc` are added
  alongside the existing `/v1/buckets…` paths, so the served spec describes the whole HTTP surface.
  `docs/rest-api.openapi.yaml` stays at its current path (the README links it and
  `scripts/verify-docs.sh` enforces that link) and remains the single source — it is packaged into the
  jar at build time rather than duplicated.
- **The Insomnia collection is brought current**: it gains the full REST object surface (bucket
  create/delete/list, object upload/download/delete/list) beside the existing health/metrics/admin and
  gRPC requests, with variables for base URL and bearer token.
- **A maintained agent-handoff file**: a repo-root file that lets a fresh LLM session become productive
  without re-reading the codebase — architecture and invariants, conventions (TDD, OpenSpec flow),
  the deployment picture, and where things live. It is **kept current as part of every subsequent
  feature**, which is itself a stated requirement rather than a hope.

No breaking changes: all additions are new routes and new/updated documentation artifacts.

## Capabilities

### New Capabilities
- `api-docs-portal`: Apollo serves its own interactive API documentation over HTTP — a Swagger UI page
  and the OpenAPI document behind it, with assets served offline from the classpath, and a completeness
  guarantee that the served document covers every HTTP endpoint the service exposes.

### Modified Capabilities
- `documentation`: adds requirements that the Insomnia collection stay current with the HTTP API, and
  that a maintained agent-handoff file exist and be updated with every feature.

## Impact

- **Code**: new `server/src/main/scala/apollostorage/http/DocsRoutes.scala`; route composition in
  `Main.scala`; `build.sbt` gains the Swagger UI webjar dependency and a resource generator that
  packages `docs/rest-api.openapi.yaml` into the jar.
- **APIs**: two new unauthenticated read-only HTTP endpoints (`/docs`, `/docs/openapi.yaml`) plus the
  static asset path they load. No existing endpoint changes.
- **Docs/tooling**: `docs/rest-api.openapi.yaml` extended; `insomnia/` collection refreshed; a new
  agent-handoff file at the repo root.
- **Security**: the docs endpoints expose only the API's shape, never data or credentials, and are
  readable without a token like `/health` — consistent with a LAN-only service. They are additive to
  the auth model, not an exception carved into it.
- **CI**: `scripts/verify-docs.sh` gains checks for the new artifacts, so they cannot silently rot.
