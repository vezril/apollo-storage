# Design: request-tracing

## Context

Apollo serves two surfaces: gRPC `ObjectApi` (power-API handlers in `ObjectApiImpl`, over an HTTP/2
handler) and a small HTTP route tree (`/health`, `/metrics`, `/admin/blob-gc`). The
`add-structured-logging` change already emits JSON via `logstash-logback-encoder` and **promotes any
SLF4J MDC entry to a top-level JSON field**. There is a clean decorator seam at the gRPC HTTP/2
boundary: `GrpcMetrics.instrument(inner, metrics)` already wraps every RPC to time it and derive the
method label. What is missing is any per-request identity, any per-request log line, and any way to
raise verbosity to TRACE. This design threads a server-minted correlation ID through the logs and back
to the caller, and makes TRACE usable.

The hard constraint is asynchrony: handlers are `Future`-based and hop threads (`AskPattern` to
entities, Pekko streams, `.flatMap` on `system.executionContext`). SLF4J's MDC is a thread-local, so a
value set on the boundary thread is not visible to a `logger.trace` running later on a pool thread.

## Goals / Non-Goals

**Goals:**
- A unique, server-generated correlation ID per request on both surfaces, on every log line for that
  request, and returned to the caller (success and failure).
- TRACE-level logs inside the service's own async call chain that still carry the ID.
- Per-request access logging (entry + completion with method/status/duration).
- Env-configurable verbosity that can isolate Apollo's code at TRACE from third-party noise.

**Non-Goals:**
- Trusting or echoing a client-supplied correlation value (always generate our own — no injection
  surface).
- Cross-service propagation (riding the ID onto HermesMQ messages) — a later additive change.
- W3C Trace Context / OpenTelemetry adoption — the eventual standard upgrade, explicitly deferred so
  the naming/plumbing here doesn't preclude it.
- Distributed sampling, span timing, or a trace backend.

## Decisions

### Name: `correlationId` (not `requestId`)
The field/concept is **`correlationId`**, carried as HTTP header `X-Correlation-Id` and gRPC metadata
`x-correlation-id`. Rationale: within the constellation the ID that matters is one that will
eventually follow a logical flow **across services** (Apollo → HermesMQ → Muses), which is a
correlation ID, not a per-hop request ID. Choosing the general name now means "accept inbound +
propagate onto Hermes" is a later additive change, not a rename. A separate per-hop `requestId` can be
added later if a hop-unique value is ever needed. Alternatives: `requestId` (too narrow — implies one
hop); `traceId` (rejected — collides with W3C Trace Context / OpenTelemetry, which is the deferred
upgrade path).

### ID generation: server-side only, short random token
The boundary **always mints** the ID (e.g. a short URL-safe random / compact UUID) and never reads a
client-supplied header. This satisfies the "not trusted" requirement and removes the log-injection /
CRLF / oversize-header concerns that echoing an inbound value would introduce — so no sanitization
logic is needed. Format is opaque; only uniqueness and log-friendliness matter.

### Two-part plumbing: boundary mints, handler re-establishes
Setting MDC at the HTTP/2 boundary does **not** reach `ObjectApiImpl`, because Pekko gRPC's internal
routing between the boundary and the power-API method runs on its own dispatchers, discarding a
thread-local set at the boundary. So the work splits:

```
  ┌─ boundary decorator (sibling of GrpcMetrics.instrument) ─────────────┐
  │  mint id → stamp into request metadata → access-log →                │
  │  add x-correlation-id response header (success AND trailers-only err) │
  └───────────────────────────┬──────────────────────────────────────────┘
                              │ id now visible in Metadata
  ┌───────────────────────────▼──────── handler entry: `guarded` ────────┐
  │  read id from metadata → MDC.put(correlationId) →                     │
  │  run body on the MDC-propagating EC → MDC.remove on completion        │
  └──────────────────────────────────────────────────────────────────────┘
```

`guarded` already wraps every RPC (it does the auth check), so it is the natural single home for the
handler-entry half — no per-RPC edits. Alternatives considered: (a) set MDC only at the boundary —
rejected, doesn't reach handlers; (b) thread an explicit `RequestContext` param through every method —
rejected, ripples into the clean `core` domain code and every signature.

### Context propagation: an MDC-propagating ExecutionContext
Introduce an `ExecutionContext` that captures the MDC map at task-submit and restores it at task-run
(a well-known wrapper pattern), and make it the EC the handlers and services actually use (the `given
ExecutionContext` in `ObjectApiImpl` / `ObjectService`, currently `system.executionContext`). With the
ID established in MDC at handler entry, every downstream `.map`/`.flatMap` and thus every
`logger.trace` inside the service carries it — with zero changes to method signatures. Third-party
async code (netty, r2dbc, Pekko internals) will not propagate it, which is fine: we only need it on
**our** logs.

### HTTP surface: a single directive
For the route tree, one Pekko HTTP directive mints the ID, puts it in MDC, adds the `X-Correlation-Id`
response header (including on rejections/failures), and runs the inner route. Simpler than gRPC — no
metadata bridge needed since the directive and route share the request scope.

### Error path carries the ID
On a gRPC error the boundary still returns an `HttpResponse` (trailers-only), so the same code path
adds the response header uniformly. Additionally the domain→gRPC error mapping appends `(cid=<id>)` to
the status message, so a troubleshooter sees the ID in the error text itself, not only in metadata.

### Verbosity: env-driven root + dedicated `apollostorage` logger
`logback.xml` gains `<root level="${LOG_LEVEL:-INFO}">` and a
`<logger name="apollostorage" level="${LOG_LEVEL_APOLLO:-INFO}">`, so `LOG_LEVEL_APOLLO=TRACE` lights
up Apollo's code while pekko/netty/r2dbc stay at the root level. A log taxonomy guides seeded
statements: INFO = lifecycle + access line; DEBUG = auth decision, command dispatch, read-model query;
TRACE = per-chunk sizes, checksums, entity command/event, metadata keys.

## Risks / Trade-offs

- **MDC-propagating EC has edge cases** (a stale MDC leaking if capture/restore is asymmetric, or an
  entry surviving into an unrelated task) → keep the wrapper minimal and symmetric (snapshot on
  submit, set-then-clear on run), and always `MDC.remove`/reset at handler completion so nothing
  leaks between requests on a reused thread.
- **The metadata bridge depends on Pekko gRPC exposing request `Metadata` to the handler** (it does —
  `guarded` already receives `Metadata`) → read the ID there; if the boundary stamp is ever missed,
  `guarded` mints a fallback ID so a handler is never un-correlated.
- **TRACE can leak sensitive data** → normative constraint: never log object payload bytes or auth
  tokens at TRACE; object names + sizes + metadata keys only.
- **Access logging adds a line per request** → at INFO this is intended and low-volume for a homelab;
  it reuses the existing boundary decorator so it adds no per-RPC cost.
- **Two mint sites (gRPC boundary, HTTP directive)** → share one small correlation-ID util (mint +
  header/metadata names) so the two surfaces cannot drift.

## Migration Plan

Additive and backward-compatible: a new response header/metadata field and new (default-INFO) env
knobs; no request-side change, no breaking change. Deploy is a normal image roll. Rollback is reverting
the image; the new env vars are optional and default to today's behavior (INFO, no TRACE). The gRPC
error message gains a trailing `(cid=…)` — cosmetic, non-breaking for clients that match on status
codes.

## Open Questions

- **ID format** — compact UUID vs a shorter base32/URL-safe token. Leaning short-and-log-friendly;
  not load-bearing, decide at implementation.
- **Which EC to wrap** — replace the `given ExecutionContext` at each use site, or register a
  dedicated Pekko dispatcher and select it. Prefer the least-invasive that still reaches the service
  code; confirm during apply that boundary-set context actually reaches `guarded`.
