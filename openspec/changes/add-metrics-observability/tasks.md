# Tasks — add-metrics-observability

TDD is non-negotiable: every implementation task is preceded by a failing-test task and
followed by a refactor + run-tests task. Metrics are **on by default** and disableable;
new tests exercise both the enabled endpoint and the disabled (404) path.

## 1. Dependencies & configuration

- [x] 1.1 Add the Prometheus JVM client to `build.sbt` (`io.prometheus` simpleclient,
  simpleclient_hotspot, simpleclient_common), pinned to one version
- [x] 1.2 Config in `AppConfig` + `application.conf`: `apollostorage.metrics.enabled`
  (default `true`, env `METRICS_ENABLED`)

## 2. Metrics registry & collectors

- [x] 2.1 **Red**: registry tests — a `MetricsRegistry` exposes an app `CollectorRegistry`;
  scraping renders exposition text; build-info and readiness gauges reflect version and
  the readiness flag (design D40/D43)
- [x] 2.2 **Green**: `MetricsRegistry` — own `CollectorRegistry`, register JVM
  `DefaultExports`, `apollostorage_build_info` gauge, `apollostorage_ready` gauge bound to
  the shared readiness `AtomicBoolean`

## 3. `/metrics` endpoint

- [x] 3.1 **Red**: route tests — `GET /metrics` returns `200` with
  `text/plain; version=0.0.4` when enabled, `404` when disabled, and needs no auth
  (design D40/D44)
- [x] 3.2 **Green**: metrics route rendering the registry; wire it into the HTTP server
  alongside `/health`, gated by `metrics.enabled`

## 4. gRPC request instrumentation

- [x] 4.1 **Red**: instrumentation tests — a completed RPC increments
  `apollostorage_grpc_requests_total{method,status}` and observes
  `apollostorage_grpc_request_duration_seconds{method}`; a failing RPC records its non-OK
  status; method label is the RPC name, never a bucket/object (design D41)
- [x] 4.2 **Green**: metrics decorator around the gRPC handler in `GrpcServer` — derive
  method from the request path, time to response completion, read outcome from
  `grpc-status` (fallback HTTP status); no changes to `ObjectApiImpl`

## 5. Blob store instrumentation

- [x] 5.1 **Red**: blob-metric tests — a put/get/delete records the operation histogram,
  the operation counter with success/failure outcome, and the bytes counter by direction
  (design D42)
- [x] 5.2 **Green**: instrument `FileSystemBlobStore` put/get/delete (latency, outcome,
  bytes) against the registry

## 6. Wiring

- [x] 6.1 **Green**: wire the registry, `/metrics` route, gRPC decorator, and blob
  instrumentation from config in `Main`; disabled ⇒ no collectors/wrappers installed

## 7. Deployment, docs & verification

- [x] 7.1 README "Metrics & monitoring": the `/metrics` endpoint, the exposed metric
  families, and a Prometheus `scrape_config` snippet targeting `:8080/metrics`
- [x] 7.2 Document `METRICS_ENABLED` in the Configuration table; note `/metrics` in the
  gRPC/HTTP overview
- [x] 7.3 **Verify**: run a container and scrape `/metrics` — after driving a few RPCs the
  gRPC, blob, JVM, build-info, and readiness families are present; `METRICS_ENABLED=false`
  yields `404`
- [x] 7.4 **Refactor + verify**: run full unit + integration suites and `scalafmtCheckAll`
