## Context

ApolloStorage runs as a Pekko cluster serving a gRPC object API over a
`FileSystemBlobStore` + PostgreSQL event journal, with a read-model projection. The only
external signal today is `GET /health` (a boolean UP/DOWN, design D5). The homelab plan
(observability design note, backlog #8) is a NAS-hosted Prometheus + Grafana that scrapes
each service's `/metrics`. This change makes ApolloStorage a first-class scrape target.

Constraints carried from prior designs:
- The HTTP server (port `8080`) already hosts `/health`; the gRPC surface is a separate
  HTTP/2 handler on `8443` (D7), optionally TLS/auth-gated (D34–D39).
- `/health` and the gRPC health service are deliberately **unauthenticated** (D37).
- Pekko's commercial telemetry (Cinnamon) is not an option; instrumentation is manual.

## Goals / Non-Goals

**Goals:**
- A Prometheus text-format `/metrics` endpoint scrapeable on the existing HTTP port.
- gRPC request throughput, latency, and error metrics covering **all** RPCs (unary +
  streaming) without per-RPC code in `ObjectApiImpl`.
- Blob-store operation latency, throughput (bytes), and outcome.
- JVM health (heap, GC, threads, FDs) and build-info + readiness gauges.
- Zero new ports, no new secret, negligible runtime overhead, default-on.

**Non-Goals:**
- Dashboards, alert rules, and the Prometheus/Grafana deployment (they live on the NAS;
  this change only documents a scrape snippet).
- Distributed tracing (OpenTelemetry/Tempo) — deferred (observability note).
- Push/remote-write, a separate metrics port, or per-tenant metrics.
- Deep Pekko cluster/sharding/projection internals beyond what is cheaply reachable;
  richer cluster metrics can follow.

## Decisions

### D40 — Prometheus JVM client, pull-based text exposition on the HTTP port
Use the `io.prometheus` simpleclient family (`simpleclient`, `simpleclient_hotspot`,
`simpleclient_common`) with a single app-owned `CollectorRegistry`. Serve
`GET /metrics` from the **existing Pekko HTTP server on `8080`**, returning
`TextFormat.CONTENT_TYPE_004`. Rationale: pull model is the Prometheus default and needs
no outbound config; reusing port `8080` means the port is already exposed and firewalled
like `/health`. Alternatives: Micrometer facade (extra abstraction, no benefit for one
backend); Pekko Management `/metrics` on `8558` (cluster-internal port, awkward to scrape,
couples app metrics to the management bind); a dedicated metrics port (more surface, no
gain on a LAN).

### D41 — Instrument gRPC at the HTTP/2 handler boundary, not in `ObjectApiImpl`
Wrap the composed gRPC handler (`HttpRequest => Future[HttpResponse]`) in `GrpcServer`
with a metrics decorator that: derives the **method** label from the request path
(`/apollostorage.grpc.ObjectApi/CreateBucket` → `CreateBucket`), times the response, and
records the outcome from the `grpc-status` trailer/header (falling back to HTTP status).
It emits `apollostorage_grpc_requests_total{method,status}` (counter) and
`apollostorage_grpc_request_duration_seconds{method}` (histogram). Rationale: one wrapper
covers every current and future RPC — including client/server-streaming — uniformly, and
keeps `ObjectApiImpl` free of cross-cutting code. The power-API migration (D35) is not
disturbed. Alternative: per-RPC instrumentation inside each handler (repetitive,
easy to miss a method, muddies streaming semantics). Trade-off: method is parsed from the
path rather than typed codegen — acceptable, and the label set is closed by the `.proto`.

### D42 — Instrument the blob store at the `FileSystemBlobStore` boundary
Record, per operation (`put`/`get`/`delete`): a duration histogram
`apollostorage_blob_operation_duration_seconds{operation}`, a counter
`apollostorage_blob_operations_total{operation,outcome}` (`outcome` = success/failure),
and `apollostorage_blob_bytes_total{direction}` (read/written). Rationale: the local disk
(and later the NFS mount) is the expected hot spot; measuring at the store boundary
attributes I/O precisely regardless of which RPC drove it. Alternative: infer bytes from
gRPC payloads (misses on-disk amplification and can't see NFS latency).

### D43 — JVM, build-info, and readiness collectors
Register `DefaultExports` (heap, GC, threads, FDs, classloading) once. Add
`apollostorage_build_info{version="…"} 1` from `BuildInfo`, and an
`apollostorage_ready` gauge (1 when the readiness flag is set, else 0) sharing the same
`AtomicBoolean` as `/health`. Rationale: standard JVM dashboards work out of the box;
build-info lets Grafana correlate behaviour with a deployed version; the readiness gauge
gives alerting a numeric signal without scraping `/health` separately.

### D44 — Default-on, config-toggleable, unauthenticated
`apollostorage.metrics.enabled` (default `true`, env `METRICS_ENABLED`). When disabled,
`/metrics` returns `404` and no collectors/wrappers are installed (zero overhead). The
endpoint is **unauthenticated** even when API auth/TLS are on — it carries operational
telemetry, no object data or bucket contents — mirroring the `/health` carve-out (D37).
Rationale: Prometheus scraping is simplest unauthenticated on a trusted LAN; metric names
and label *values* are bounded (bucket/object names are **never** used as labels, avoiding
both cardinality blow-up and information leakage). Alternative: gate `/metrics` behind a
token (friction for scrapers, and nothing sensitive is exposed).

## Risks / Trade-offs

- **Label cardinality explosion** → labels are drawn only from closed sets (RPC method
  names from the `.proto`; fixed operation/outcome/direction values). Bucket and object
  names, tokens, and remote addresses are never labels.
- **Histogram bucket choice misleads latency reading** → use Prometheus default latency
  buckets for gRPC and a byte-appropriate set for blob size; document that they can be
  tuned later without a contract break.
- **Metrics wrapper adds request latency** → work is in-memory counter/histogram observes
  (sub-microsecond); the wrapper only reads the status and stops a timer. Negligible.
- **Registry/endpoint leaks internal detail** → exposition is opt-out-able and carries no
  payload data; on the LAN this is acceptable (access-model note: network is the boundary).
- **Streaming RPC duration semantics** → duration is measured to response completion;
  for server-streaming that is stream close, which is the meaningful figure for scraping.

## Migration Plan

Additive and backward-compatible — no data migration. Deploy: the new dependency and
`/metrics` endpoint ship in the image; `METRICS_ENABLED` defaults on. Point Prometheus at
`http://<host>:8080/metrics` (README snippet). Rollback: set `METRICS_ENABLED=false`
(endpoint disappears, instrumentation goes dormant) or redeploy the prior image; nothing
else depends on the metrics.

## Open Questions

- Latency histogram buckets — accept Prometheus defaults for v1, revisit once Grafana
  panels show the real distribution. (Leaning: defaults now.)
- Whether to add basic cluster-membership gauges (members up, unreachable) in this change
  or a follow-up — cheap to read from `Cluster(system)`, but not required for the core
  scrape target. (Leaning: include a small readiness/up gauge now, defer richer cluster
  metrics.)
