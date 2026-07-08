## Why

The blob store and the bucket aggregate are implemented and tested, but nothing
outside the process can drive them — there is no way for a client to create a
bucket or upload, download, and delete an object. Design decision D7 reserves a
gRPC surface for the object API (with HTTP kept only for container health). This
change adds that surface so ApolloStorage becomes actually usable end-to-end.

## What Changes

- Introduce a **gRPC object API** (Pekko gRPC + protobuf) exposing:
  - **Bucket ops**: `CreateBucket`, `DeleteBucket`.
  - **Object ops**: `PutObject` (client-streaming — a metadata header frame then
    payload chunks), `GetObject` (server-streaming — a metadata header frame then
    payload chunks), `HeadObject` (metadata only), `DeleteObject`.
- Wire the API to the existing seams: `PutObject`/`DeleteObject` call
  `ObjectService`; `GetObject`/`HeadObject` resolve the object's `BlobRef` via
  `BucketEntity.GetObject` and stream from the `BlobStore`; bucket ops issue
  `CreateBucket`/`DeleteBucket` through the entity.
- Add the standard **`grpc.health.v1` health service** reflecting readiness
  (design D7); the existing HTTP `/health` remains for container orchestration.
- Bind a **gRPC port** in the runtime (HOCON + env override), alongside HTTP.
- Map domain errors to gRPC status codes (e.g. `BucketNotFound` → `NOT_FOUND`,
  `BucketAlreadyExists` → `ALREADY_EXISTS`, invalid name → `INVALID_ARGUMENT`,
  checksum mismatch → `FAILED_PRECONDITION`).

Out of scope (future changes): bucket/object **listing** (needs read-side
projections), multipart/resumable uploads, range/partial reads, TLS and
authentication/authorization, and migrating event serialization to protobuf
(events remain Jackson CBOR — D4).

## Capabilities

### New Capabilities
- `object-api`: the client-facing gRPC surface for bucket and object lifecycle —
  streaming upload/download, metadata, deletion, domain-error-to-status mapping,
  and the gRPC health service.

### Modified Capabilities
<!-- None. The API consumes existing seams (ObjectService, BucketEntity, BlobStore)
     without changing their spec requirements. The HTTP health endpoint
     (service-runtime) is unchanged; gRPC health is added additively per D7. -->

## Impact

- **Build**: add the `sbt-pekko-grpc` plugin and protobuf definitions
  (`server/src/main/protobuf`); gRPC code is generated at compile time. This is
  the first protobuf in the build (D4 noted this makes a future protobuf event
  migration coherent, but events stay CBOR here).
- **Affected code (`server`)**: protobuf service/message definitions; a gRPC
  service implementation delegating to `ObjectService` / `BucketEntityManager` /
  `BlobStore`; error-to-status mapping; gRPC + HTTP binding in `Main`; gRPC health
  wired to the readiness flag.
- **Configuration**: new `apollostorage.grpc.port` (env `GRPC_PORT`).
- **Deployment**: the image/compose expose the gRPC port; README documents the
  API and a `grpcurl` example.
- **Dependencies**: `pekko-grpc-runtime` (+ codegen plugin); no change to the
  journal or blob store.
- **Tests**: gRPC integration tests (in-process Pekko gRPC client) for
  create/put/get/head/delete round-trips, streaming large payloads, checksum-
  mismatch and not-found status mapping, and the health service.
