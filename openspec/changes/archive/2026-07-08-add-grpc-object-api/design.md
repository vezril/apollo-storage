# Design — add-grpc-object-api

## Context

`ObjectService`, `BucketEntity`/`BucketEntityManager`, and the `BlobStore` are
implemented and tested but have no external caller. Design decision D7 reserves a
gRPC surface for the object API (HTTP stays only for container health). This change
adds that surface. The pure domain, the journal, and the blob store are unchanged;
this is a new adapter layer over existing seams. Decision numbering continues the
project log (previous change ended at D14).

## Goals / Non-Goals

**Goals:**
- A gRPC API for the bucket/object lifecycle: create/delete bucket, streaming
  put/get, head (metadata), delete.
- Backpressured streaming that never buffers a whole payload in memory.
- Domain errors mapped to meaningful gRPC status codes.
- A `grpc.health.v1` service reflecting readiness (D7).

**Non-Goals:**
- Listing (needs read-side projections), multipart/resumable uploads, range reads,
  TLS, auth/authorization, and protobuf event serialization (events stay CBOR, D4).

## Decisions

### D15 — Pekko gRPC (ScalaPB) for the surface

Use Pekko gRPC (`sbt-pekko-grpc`, ScalaPB-generated) rather than grpc-java directly.
- **Why**: it models client/server streaming as Pekko Streams `Source`s, which
  compose directly with `ObjectService`/`BlobStore` (already `Source[ByteString]`),
  and runs on the existing `ActorSystem`.
- **Alternative**: grpc-java + manual `StreamObserver`s (rejected — imperative
  back-pressure, no Streams integration); an HTTP/REST object API (rejected —
  contradicts D7 and lacks first-class bidi streaming).

### D16 — Header-then-chunks streaming framing

`PutObject` is client-streaming: the **first** message is a metadata header
(`bucket`, `name`, `content_type`, optional expected `crc32c`/`md5`); every
subsequent message carries a payload chunk. `GetObject` is server-streaming with the
symmetric shape (a header message, then chunks). The server validates the header,
then feeds the chunk stream to `ObjectService.commit` as `Source[ByteString]`.
- **Why**: one RPC, fully streamed and backpressured, no server-side session state.
- **Alternative**: a separate `InitiateUpload` + per-chunk unary RPCs (rejected —
  extra round trips and session bookkeeping).

### D17 — Two ports; HTTP/2 cleartext (h2c) for v1

Bind gRPC on its own port (`apollostorage.grpc.port`, env `GRPC_PORT`) via Pekko
gRPC's handler; keep the HTTP/1 health endpoint on its existing port. gRPC is served
h2c (no TLS) for the trusted homelab LAN.
- **Why**: simplest, and container/orchestration health tooling stays on plain HTTP.
- **Trade-off**: h2c has no transport security — acceptable on a trusted network;
  **TLS is a tracked future change** (D7 mentions auth/TLS as later work).
- **Alternative**: single-port h1/h2 multiplexing (rejected — added complexity for
  no homelab benefit).

### D18 — Central domain-error → gRPC status mapping

One mapping turns `DomainError` and blob failures into `Status`:
`BucketNotFound`/`ObjectNotFound` → `NOT_FOUND`, `BucketAlreadyExists` →
`ALREADY_EXISTS`, `InvalidBucketName`/`InvalidObjectName` → `INVALID_ARGUMENT`,
`ChecksumMismatch` → `FAILED_PRECONDITION`; unexpected failures → `INTERNAL`.
- **Why**: predictable client semantics; keeps status logic out of the handlers.

### D19 — gRPC health reflects the readiness flag

Implement `grpc.health.v1.Health` (`Check`/`Watch`) backed by the same readiness
`AtomicBoolean` the HTTP endpoint uses: `SERVING` when Postgres + blob store are
ready, else `NOT_SERVING` (design D7).

### D20 — Download resolves the ref through the entity

`GetObject`/`HeadObject` resolve the current `BlobRef` via
`BucketEntity.GetObject(name)`. Absent object → `NOT_FOUND`; otherwise `HeadObject`
returns metadata/checksums/generation and `GetObject` streams `BlobStore.get(ref)`.
A `BlobRef` present in state but missing on disk is an `INTERNAL` error (should not
happen given the two-phase commit, D12).

## Risks / Trade-offs

- **pekko-grpc build/codegen complexity** → Mitigation: pin plugin/runtime versions,
  keep the proto minimal, verify generated code compiles in CI.
- **Large uploads exhausting memory** → Mitigation: never materialize the whole
  payload; chunks stream straight into `ObjectService` → disk (blob-storage D10/D11).
  Configure gRPC max inbound message size for the chunk frame.
- **Mid-stream upload failure** → Mitigation: `ObjectService` already persists no
  event and leaves no orphan on a failed stream (D12); the RPC returns an error.
- **h2c has no transport security** → Mitigation: trusted LAN only; TLS is future.

## Migration Plan

Additive: a new gRPC port and the `sbt-pekko-grpc` plugin; no journal/blob changes.
The image and compose expose the gRPC port. **Rollback**: revert — the HTTP health
endpoint and all storage remain intact.

## Open Questions

- Default/recommended chunk size and a maximum object-size limit.
- Whether `GetObject` should re-verify checksums on read (scrubbing) or trust D11/D12.
- Whether to expose a minimal `ListBuckets` before projections land, or wait.
