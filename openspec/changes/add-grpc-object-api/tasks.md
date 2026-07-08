# Tasks — add-grpc-object-api

TDD is non-negotiable: every implementation task is preceded by a failing-test
task and followed by a refactor + run-tests task. Do not start an implementation
task while its tests are green or missing.

The API is a `server`-module adapter over existing seams (`ObjectService`,
`BucketEntity`/`BucketEntityManager`, `BlobStore`); the pure domain and the
journal are unchanged.

## 1. Build & protobuf scaffolding

- [x] 1.1 Add `sbt-pekko-grpc` plugin + `pekko-grpc-runtime`; enable the codegen for the `server` module
- [x] 1.2 Define `object_api.proto` — `ObjectApi` service (`CreateBucket`, `DeleteBucket`, `PutObject` client-stream, `GetObject` server-stream, `HeadObject`, `DeleteObject`) and messages (header + chunk framing, design D16)
- [x] 1.3 Verify code generation + compile of the generated service/client stubs

## 2. Error mapping & bucket lifecycle

- [x] 2.1 **Red**: mapping tests — `DomainError`/`ChecksumMismatch` → gRPC `Status` (NOT_FOUND / ALREADY_EXISTS / INVALID_ARGUMENT / FAILED_PRECONDITION / INTERNAL) (design D18)
- [x] 2.2 **Green**: central `DomainStatus` mapping
- [x] 2.3 **Red**: in-process gRPC tests — `CreateBucket` then `DeleteBucket` succeed; duplicate create ⇒ `ALREADY_EXISTS`; invalid name ⇒ `INVALID_ARGUMENT` (edge cases)
- [x] 2.4 **Green**: bucket handlers delegating to the entity via `BucketEntityManager`

## 3. Streaming upload

- [x] 3.1 **Red**: `PutObject` tests — header-then-chunks upload commits and returns generation/checksums/size; large multi-chunk payload round-trips; checksum mismatch ⇒ `FAILED_PRECONDITION` and no object committed (edge cases, design D16)
- [x] 3.2 **Green**: `PutObject` handler — validate header, feed chunk `Source[ByteString]` to `ObjectService.commit`
- [x] 3.3 **Refactor**: extract header/validation; run the API suite

## 4. Streaming download & metadata

- [x] 4.1 **Red**: `GetObject` tests — header + chunks return byte-identical payload and report content type/checksums; missing object ⇒ `NOT_FOUND` (edge cases, design D20)
- [x] 4.2 **Green**: `GetObject` handler — resolve `BlobRef` via `BucketEntity.GetObject`, stream from `BlobStore`
- [x] 4.3 **Red**: `HeadObject` tests — returns metadata/checksums/generation, no payload; missing ⇒ `NOT_FOUND` (edge cases)
- [x] 4.4 **Green**: `HeadObject` handler

## 5. Deletion

- [x] 5.1 **Red**: `DeleteObject` tests — deletes so a later `HeadObject` ⇒ `NOT_FOUND`; deleting a missing object ⇒ `NOT_FOUND` (edge cases)
- [x] 5.2 **Green**: `DeleteObject` handler delegating to `ObjectService.delete`

## 6. gRPC health

- [x] 6.1 **Red**: `grpc.health.v1.Health.Check` tests — `SERVING` when ready, `NOT_SERVING` before readiness (edge cases, design D7/D19)
- [x] 6.2 **Green**: health service backed by the readiness flag

## 7. Runtime wiring, deployment & docs

- [x] 7.1 **Red**: startup/binding test — the gRPC server binds its configured port and serves a smoke RPC; `GRPC_PORT` override honored (edge cases, design D17)
- [x] 7.2 **Green**: bind the gRPC handler (h2c) in `Main` alongside HTTP health; `apollostorage.grpc.port` config; construct the `ObjectApi` with `ObjectService`/`BlobStore`/entity manager
- [x] 7.3 Update `Dockerfile`/native-packager `EXPOSE` + `docker-compose.yml` to publish the gRPC port; README: API overview, config row, and a `grpcurl` example
- [x] 7.4 **Refactor + verify**: run full unit + integration suites, `scalafmtCheckAll`, and confirm a deployment serves gRPC + reports healthy
