# Change: add-request-tracing

## Why

When you send a request today the console tells you almost nothing — there is no per-request log
line and no way to tie the scattered log events of one operation together. Debugging a failed upload
means guessing which lines belong to it. This adds a **correlation ID** minted per request, threaded
through every log line for that request (including new TRACE-level detail) and returned to the caller,
so a single ID ties the whole operation together across the logs and back to the client.

## What Changes

- **New `correlationId`**, server-generated per request (a short random token). It is placed in the
  logging MDC so — building on the existing structured-logging capability, which already promotes MDC
  entries to top-level JSON fields — it appears on every log line for that request automatically.
- **Returned to the caller** on every response: HTTP header `X-Correlation-Id`, gRPC response
  metadata `x-correlation-id`, and additionally embedded in the gRPC error trailer message
  (`… (cid=ab12cd)`) so a failing call surfaces its ID where a troubleshooter will see it.
- **Context propagation across async hops**: an MDC-propagating `ExecutionContext` so a plain
  `logger.trace` deep in `ObjectService` / `BlobStore` / entity code still carries the `correlationId`,
  despite the `Future`/actor thread-hopping between the request boundary and that code.
- **Per-request access logging** at INFO: one line on entry and one on completion (method, gRPC/HTTP
  status, duration, and — where known — bucket/object and byte count), for both the gRPC and HTTP
  surfaces, reusing the existing handler-boundary decorator seam.
- **Configurable verbosity**: the root log level and a dedicated `apollostorage`-logger level become
  env-driven (`LOG_LEVEL`, `LOG_LEVEL_APOLLO`) so TRACE can be dialed up on Apollo's own code without
  drowning in third-party (pekko/netty/r2dbc) noise.
- **Seeded DEBUG/TRACE statements** in the request path (auth decision, command dispatch, read-model
  queries at DEBUG; per-chunk sizes, checksums, entity command/event, metadata keys at TRACE).
- **Non-goals (explicit, to keep the door open):** inbound correlation IDs are **ignored** for v1 —
  the server always generates its own (no client-supplied value is trusted or echoed), so there is no
  log-injection surface; cross-service propagation (riding the ID onto HermesMQ messages) and W3C
  Trace Context / OpenTelemetry are deferred, additive follow-ups, not part of this change.

## Capabilities

### New Capabilities

- `request-tracing`: a per-request correlation ID minted server-side, propagated through the logging
  context across async boundaries, surfaced on every log line and returned to the caller; plus
  per-request access logging and env-configurable log verbosity (including TRACE for Apollo's code).

### Modified Capabilities

<!-- None. `structured-logging` already promotes MDC entries to JSON fields, so correlationId flows
     without a requirement change; this capability builds on it rather than modifying it. -->

## Impact

- **New code**: a correlation-ID utility (mint + header/metadata names), a boundary decorator
  (sibling of `GrpcMetrics.instrument`) that mints the ID, stamps it into request metadata, access-logs,
  and adds the response header; an MDC-propagating `ExecutionContext`; a Pekko HTTP directive for the
  HTTP routes (`/health`, `/metrics`, `/admin/blob-gc`).
- **Touched code**: `Main` (wire the decorator + directive), `ObjectApiImpl` (`guarded` re-establishes
  MDC from metadata and runs on the propagating EC; seeded DEBUG/TRACE), `ObjectService` / `BlobStore`
  (seeded TRACE), `logback.xml` (env-driven levels + a dedicated `apollostorage` logger).
- **Config/env**: new `LOG_LEVEL` (root) and `LOG_LEVEL_APOLLO` (Apollo code) knobs; no new secrets.
- **API surface**: additive only — a new response header/metadata field; no request-side change, no
  breaking changes. gRPC error messages gain a trailing `(cid=…)`.
- **Depends on**: the shipped `structured-logging` capability (MDC → JSON field promotion).
