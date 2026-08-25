# Tasks — add-s3-backend-and-rest-api

Two independent capabilities in one change, sequenced so each lands on its own: **spike → S3 backend →
REST → deploy**. TDD-first (failing test → code). S3 backend tests run against a MinIO testcontainer
(S3-compatible), mirroring the existing Postgres-testcontainer pattern; the QuObjects-specific quirks
are settled by the spike.

## 1. Spike (gate) — QuObjects S3 fidelity — ✅ DONE, PASSED

- [x] 1.1 Ran a throwaway Alpakka-S3 (`pekko-connectors-s3` 1.2.0) client against live QuObjects
  (`https://mimir…:8010`, path-style). **All green:** multipart upload → `CompleteMultipartUpload`
  (multipart ETag `…-1`), `getObject` round-trip (6 MiB, size-verified), `ListObjectsV2` (prefix →
  key/size/lastModified), `ListMultipartUploads` (reachable), `makeBucket`/`listBuckets`/
  `deleteObject`, path-style addressing. QuObjects is S3-compatible enough — **Option B is viable.**
- [x] 1.2 Upload path = **native multipart** (verified). Caveat: `AbortMultipartUpload` of a *real*
  incomplete upload wasn't exercised (Alpakka's high-level API completes atomically; none existed) —
  the `listTempArtifacts`/`deleteTempArtifact` reconciliation may need the AWS SDK v2 S3 client
  directly for list/abort-incomplete. Confirm during §4.
- Findings folded into §2–§3: dep pin + TLS handling below.

## 2. Config & backend selection

- [x] 2.1 Test: `AppConfig` resolves `BLOB_BACKEND` (`filesystem` default | `s3`) and, for `s3`, an
  S3 section (endpoint, region, bucket, path-style) with credentials sourced from env/secrets
- [x] 2.2 Implement the config (no credential defaults in source); add `pekko-connectors-s3` %
  `1.2.0` to `build.sbt` **plus an explicit `pekko-http-xml` % `pekkoHttpVersion` pin** (the connector
  drags in `pekko-http-xml` 1.1.0, which trips Pekko's no-mixed-versions guard at runtime — spike-verified)
- [x] 2.3 TLS to QuObjects: the endpoint is HTTPS with a **self-signed, hostname-mismatched** cert
  (spike hit both `certificate_unknown` and `No name matching`). The S3 backend needs custom client
  TLS — a truststore trusting the QuObjects cert **and** hostname handling — OR (preferred) the
  QuObjects cert is regenerated with a correct SAN. Never ship trust-all in production; scope any loose
  TLS to the LAN endpoint only. Config: an `S3_TLS_TRUSTSTORE`/insecure-LAN toggle

## 3. S3BlobStore — core I/O (TDD, MinIO testcontainer)

- [x] 3.1 Test: `put` streams to S3 via multipart, computes crc32c+md5 in one pass, returns the
  `BlobRef`; a checksum mismatch aborts the upload leaving nothing retrievable
- [x] 3.2 Test: `get` streams a stored payload back; an absent reference yields `None`
- [x] 3.3 Test: `delete` removes an object (idempotent)
- [x] 3.4 Implement `S3BlobStore.put/get/delete` (multipart sink, streamed download, `BlobRef` = S3
  key verbatim)

## 4. S3BlobStore — reconciliation surface (TDD)

- [x] 4.1 Test: `listStoredBlobs`/`listBucketsOnDisk` via `ListObjectsV2` (prefix + delimiter),
  returning refs with size + last-modified; pagination beyond one page
- [x] 4.2 Test: `listTempArtifacts`/`deleteTempArtifact` via `ListMultipartUploads`/
  `AbortMultipartUpload` (incomplete uploads = the debris analog)
- [x] 4.3 Implement the four enumeration/reconciliation methods; confirm `BlobGc` reconciles against
  S3 unchanged (its requirements don't move)

## 5. Readiness & wiring

- [x] 5.1 Test: S3 readiness verifies the target bucket is reachable/exists; unreachable ⇒ fail fast
- [x] 5.2 Implement the S3 readiness check; wire `Main` to select `FileSystemBlobStore` vs
  `S3BlobStore` from `BLOB_BACKEND` (single construction point)

## 6. REST — bucket lifecycle (TDD, shared core)

- [x] 6.1 Test: `PUT/DELETE /v1/buckets/{bucket}` and `GET /v1/buckets` go through the same
  `ObjectService`/entities and produce the same event-sourced effects as gRPC (JSON listing)
- [x] 6.2 Implement the bucket routes + JSON codecs, delegating to the core

## 7. REST — objects (TDD)

- [x] 7.1 Test: `PUT .../objects/{object}` stores the raw body (content type from header, optional
  expected-checksum header enforced); `GET` streams it back with metadata headers
- [x] 7.2 Test: `HEAD` returns content-type/size/generation/crc32c/md5 headers and no body; `DELETE`
  removes the object
- [x] 7.3 Test: `GET .../objects?prefix=&pageToken=` lists from the read model with pagination (JSON)
- [x] 7.4 Implement the object routes (streaming entities, metadata-as-headers, listing)

## 8. REST — auth, tracing, errors

- [x] 8.1 Test: scoped auth on REST — write scope for mutations, read for reads; `401` missing/unknown
  token, `403` insufficient scope
- [x] 8.2 Implement via the existing `authorizeHttp`; mount routes under the `RequestTracing` directive
  (correlation ids + access logs); JSON error envelope carrying `correlationId`

## 9. Contract, transport, docs

- [x] 9.1 Author the Apollo-local OpenAPI document for the REST surface; wire the docs check to keep it
  referenced
- [x] 9.2 Transport posture: extend the TLS toggle to the HTTP listener (or explicitly document
  cleartext-REST as LAN-only) — decide at apply time per design Open Questions

## 10. Deployment (coordinate with Codex)

- [ ] 10.1 (at drive-it/release) Hand Codex the chart change: `BLOB_BACKEND=s3` + S3 endpoint/credentials
  Secret (`S3_ENDPOINT/S3_BUCKET/S3_ACCESS_KEY/S3_SECRET_KEY/S3_PATH_STYLE=true`, plus TLS: fix the
  QuObjects cert SAN or set `S3_TLS_INSECURE=true` LAN-only), and **drop the `apollo-blobs` NFS PVC**
  (only the local-path Postgres journal PVC remains). Deferred to the release step.

## 11. Verify

- [x] 11.1 Full suite green (unit + MinIO/Postgres testcontainers); `-Werror` clean; scalafmt/scalafix
  pass
- [x] 11.2 Mechanics verified: the QuObjects S3 path is proven by the spike (real multipart round-trip),
  the S3BlobStore MinIO IT (7 tests: put/get/delete/list/readiness), and the REST adapter spec (10 tests:
  streaming/headers/auth/errors). NOTE: a full running-instance smoke (Apollo with `BLOB_BACKEND=s3`
  serving REST+gRPC against live QuObjects) is a **post-deploy** step, to run once Codex applies the chart.
