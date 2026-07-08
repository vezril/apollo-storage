## Why

ApolloStorage exposes only a binary `/health` probe — there is no way to see request
rates, latencies, error ratios, blob-store I/O, or JVM/cluster health. The planned
homelab observability stack (Prometheus + Grafana on the NAS) needs a scrape target to
turn the service into something operable: to spot latency regressions, a filling disk,
projection lag, or a struggling node before they become outages.

## What Changes

- Add a Prometheus **`/metrics`** endpoint on the existing HTTP port (`8080`), in the
  standard text exposition format, scrapeable by Prometheus.
- Instrument the **gRPC API**: per-RPC request counts and latency histograms labelled by
  method and outcome (gRPC status), captured at the HTTP/2 boundary so every RPC —
  unary and streaming — is covered without touching `ObjectApiImpl`.
- Instrument the **blob store**: operation counts, latency, bytes read/written, and
  outcome for put/get/delete at the `FileSystemBlobStore` boundary.
- Export **JVM** metrics (heap, GC, threads, file descriptors) and a **build-info** gauge
  (version label), plus a **readiness** gauge mirroring the `/health` state.
- Metrics collection is **on by default** and disableable via config
  (`apollostorage.metrics.enabled`); the endpoint is **unauthenticated** (no object data,
  LAN-scraped), consistent with the `/health` carve-out.

## Capabilities

### New Capabilities
- `observability`: a Prometheus metrics endpoint and the application/JVM metrics it
  exposes for scrape-based monitoring and alerting.

### Modified Capabilities
<!-- None: /health (service-runtime) and the gRPC contract (object-api) are unchanged;
     instrumentation is additive and behaviourally transparent. -->

## Impact

- **Code**: new metrics registry + `/metrics` route wired into the existing HTTP server
  (`HttpServer`/`HealthRoutes` area); a metrics wrapper around the gRPC handler in
  `GrpcServer`; instrumentation hooks in `FileSystemBlobStore`; wiring in `Main`.
- **Dependencies**: adds the Prometheus JVM client (`io.prometheus` simpleclient family:
  core, hotspot/JVM collectors, exposition format). No Pekko version impact.
- **Deployment**: `/metrics` reachable on the already-exposed port `8080`; a Prometheus
  scrape-config snippet documented in the README. No new port, no new secret.
- **Runtime**: negligible overhead (in-process counters/histograms; pull-based scrape).
