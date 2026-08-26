## 1. Complete the OpenAPI document

- [x] 1.1 Add `/health` to `docs/rest-api.openapi.yaml` (200 UP / 503 DOWN body shape, no security)
- [x] 1.2 Add `/metrics` (200 Prometheus text exposition, `text/plain`)
- [x] 1.3 Add `/admin/blob-gc` (POST, `delete` parameter, `GcReport` response schema, bearer + write
      scope, documented as present only when blob GC is enabled)
- [x] 1.4 Add `/docs` and `/docs/openapi.yaml` as documented unauthenticated endpoints
- [x] 1.5 Verify the whole document still parses as valid OpenAPI 3.0.3

## 2. Build wiring (tests first)

- [x] 2.1 Test: the Swagger UI webjar assets resolve from the classpath at the pinned version path
      (fails if a version bump breaks the asset path — design D1)
- [x] 2.2 Add `org.webjars:swagger-ui:5.25.3` to `server` dependencies in `build.sbt`
- [x] 2.3 Test: the packaged `openapi.yaml` resource is present on the classpath and byte-identical to
      `docs/rest-api.openapi.yaml` (design D2)
- [x] 2.4 Add the `Compile / resourceGenerators` task copying `docs/rest-api.openapi.yaml` into managed
      resources
- [x] 2.5 Run 2.1 and 2.3 — both green

## 3. Docs routes (tests first)

- [x] 3.1 Test: `GET /docs` returns 200 `text/html` referencing the OpenAPI path and the UI assets
- [x] 3.2 Test: `GET /docs/` (trailing slash) returns the same page, not 404
- [x] 3.3 Test: `GET /docs/openapi.yaml` returns 200 and a body that parses as YAML with `openapi` and
      `paths`
- [x] 3.4 Test: the page body contains no absolute third-party URL (offline guarantee — spec:
      "No external references")
- [x] 3.5 Test: `GET /docs` and `GET /docs/openapi.yaml` succeed with authentication enabled and no
      `Authorization` header
- [x] 3.6 Test: `POST /docs/openapi.yaml` is rejected (safe methods only)
- [x] 3.7 Implement `server/src/main/scala/apollostorage/http/DocsRoutes.scala` — the HTML page, the
      spec route, and the classpath asset route
- [x] 3.8 Run the DocsRoutes tests — all green

## 4. Route composition

- [x] 4.1 Mount `DocsRoutes` in `Main.scala` alongside `healthRoutes` (unauthenticated), inside
      `RequestTracing.withCorrelationId`
- [x] 4.2 Test: with the full route tree composed, `/docs` and an existing route (`/health`) both still
      resolve — no route shadowing
- [x] 4.3 Run the full server test suite — green

## 5. Documented-surface completeness

- [x] 5.1 Test: every entry in the known public HTTP surface inventory (health, metrics, the
      `/v1/buckets…` object API, blob-gc, docs) has a corresponding path in the OpenAPI document
      (design D4)
- [x] 5.2 Test: mutating object operations declare the bearer security scheme, and `/health` + `/docs`
      declare none
- [x] 5.3 Run — green

## 6. Insomnia collection

- [x] 6.1 Add the REST object requests to `insomnia/apollostorage.insomnia_collection.json`: list
      buckets, create bucket, delete bucket, list objects, upload object, download object, delete object
- [x] 6.2 Ensure base URL and bearer token are environment variables (no hard-coded host or token), and
      the existing health/metrics/admin/gRPC requests still import
- [x] 6.3 Update `insomnia/README.md` — what is covered, how to set the environment, how to retarget to
      LAN/tailnet
- [x] 6.4 Verify: the collection JSON parses, and every documented OpenAPI path is exercised by at least
      one request

## 7. Agent handoff document

- [x] 7.1 Write `AGENTS.md` at the repo root: what Apollo is; architecture (event-sourced CQRS, bucket
      entity, blob store behind an interface, read projections); the invariants a change must not break
- [x] 7.2 Document the mandatory workflow: TDD red-green-refactor, the OpenSpec change flow
      (propose → apply → archive), branch/PR conventions (`feature/*` → `development` → `main`), and
      how releases are cut and deployed
- [x] 7.3 Document the API surface (HTTP + gRPC) with pointers to the authoritative artifacts, the
      deployment picture (k3s, QuObjects S3 backend, tailnet access), and a where-things-live map
- [x] 7.4 Record current state honestly: what is deployed, known gaps, deferred work, and the residual
      risk named in design D4
- [x] 7.5 Add the "update this file as part of every change" instruction, with what counts as requiring
      an update
- [x] 7.6 Reference `AGENTS.md` from `README.md`

## 8. Guard against rot

- [x] 8.1 Extend `scripts/verify-docs.sh`: `AGENTS.md` and the Insomnia collection exist; the collection
      parses as JSON; `AGENTS.md`'s repo-relative paths resolve
- [x] 8.2 Run `bash scripts/verify-docs.sh` — passes

## 9. Full verification

- [x] 9.1 `sbt scalafmtCheckAll` — clean
- [x] 9.2 `sbt test` — full suite green
- [x] 9.3 Run the service locally and load `/docs` in a browser: the page renders, the spec loads, and
      the network panel shows no third-party request
