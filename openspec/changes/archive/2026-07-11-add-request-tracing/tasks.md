# Tasks — add-request-tracing

Add a server-minted correlation ID threaded through logs (across async hops) and returned to the
caller, per-request access logging, and env-configurable TRACE. TDD-first: write the failing test,
then the code. Keep changes additive and backward-compatible.

## 1. Foundation: correlation-ID util + MDC-propagating EC

- [x] 1.1 Test: a `CorrelationId` util mints a unique, non-empty, log-friendly ID each call, and
  exposes the canonical header/metadata names (`X-Correlation-Id` / `x-correlation-id`)
- [x] 1.2 Implement `CorrelationId` (mint + name constants); server-only, no inbound parsing
- [x] 1.3 Test: an MDC-propagating `ExecutionContext` restores the submit-time MDC on the run thread
  and leaves no MDC behind on that thread afterward (no leak between tasks)
- [x] 1.4 Implement the MDC-propagating `ExecutionContext` wrapper (symmetric snapshot → set → clear)

## 2. gRPC surface: boundary mint + handler re-establish

- [x] 2.1 Test: the boundary decorator adds an `x-correlation-id` response header on both a success
  and a trailers-only error response
- [x] 2.2 Implement the boundary decorator (sibling of `GrpcMetrics.instrument`): mint id → stamp into
  request metadata → add response header; wire it in `Main` alongside the metrics decorator
- [x] 2.3 Test: `guarded` reads the id from request metadata into MDC so a log emitted inside the RPC
  body carries `correlationId`; and mints a fallback id if the metadata stamp is absent
- [x] 2.4 Implement `guarded` MDC set-from-metadata + run body on the propagating EC + `MDC.remove` on
  completion; make the handlers/services use the propagating EC as their `given ExecutionContext`
- [x] 2.5 Test: a TRACE line emitted deep in the async chain (object/blob service on a pool thread)
  carries the same id as the boundary — proves propagation end-to-end

## 3. HTTP surface

- [x] 3.1 Test: a route wrapped by the tracing directive returns `X-Correlation-Id`, and the value is
  present on a failing/rejected response too
- [x] 3.2 Implement the Pekko HTTP tracing directive (mint → MDC → response header → inner route) and
  wrap `/health`, `/metrics`, `/admin/blob-gc` in `Main`

## 4. Error path carries the id

- [x] 4.1 Test: a gRPC error surfaces the id both in response metadata and within the status trailer
  message (`… (cid=<id>)`)
- [x] 4.2 Implement appending `(cid=<id>)` in the domain→gRPC error mapping

## 5. Access logging + seeded DEBUG/TRACE

- [x] 5.1 Test: a handled request emits an INFO entry line and an INFO completion line carrying
  method, status, duration, and the correlation id (assert via a capturing appender/MDC)
- [x] 5.2 Implement per-request access logging on the gRPC boundary and the HTTP directive
- [x] 5.3 Seed DEBUG (auth decision, command dispatch, read-model query) and TRACE (per-chunk sizes,
  checksums, entity command/event, metadata keys) statements — no payload bytes or tokens ever logged

## 6. Verbosity config

- [x] 6.1 `logback.xml`: `<root level="${LOG_LEVEL:-INFO}">` + a dedicated
  `<logger name="apollostorage" level="${LOG_LEVEL_APOLLO:-INFO}">`
- [x] 6.2 Document the `LOG_LEVEL` / `LOG_LEVEL_APOLLO` knobs (README/config comment); confirm
  `LOG_LEVEL_APOLLO=TRACE` with root INFO isolates Apollo's trace lines from third-party noise

## 7. Verify

- [x] 7.1 Full suite green (server + core); `-Werror` compile clean; `scalafixAll --check` +
  `scalafmtCheckAll` pass
- [x] 7.2 End-to-end verified via the in-process integration suite: the gRPC ITs drive the real
  handler path through `guarded` (metadata → MDC), and the tracing/access-log specs assert the echoed
  `x-correlation-id` / `X-Correlation-Id` and the `(cid=…)` error annotation; correlated access-log
  lines were observed in test output. NOTE: a standalone container grpcurl/curl smoke was not run —
  available on request.
