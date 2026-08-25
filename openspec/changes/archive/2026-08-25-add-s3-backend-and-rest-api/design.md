# Design: add-s3-backend-and-rest-api

## Context

Apollo is a ports-and-adapters system without having named it so: the gRPC `ObjectApiImpl` is a thin
adapter over a reusable core — `ObjectService`, the sharded `BucketEntity` aggregates (event-sourced
journal + CQRS read model), and the `BlobStore` trait. `BlobStore` is a clean, complete seam with a
single implementation (`FileSystemBlobStore`): `put`/`get`/`delete` plus enumeration methods for
blob-gc (`listStoredBlobs`, `listTempArtifacts`, `deleteTempArtifact`, `listBucketsOnDisk`).

Two independent moves ride on that structure. **(1)** The QNAP already runs QuObjects (S3-compatible),
so Apollo storing bytes on its own NFS volume is redundant and is what blocks deployment (the
`apollo-blobs` NFS PVC never binds). A second `BlobStore` implementation backed by S3 lets Apollo keep
its differentiated value — the event-sourced catalog + gRPC front door — while delegating durable bytes
to the appliance. **(2)** The object API is gRPC-only; a REST adapter over the same core makes Apollo
usable from browsers, curl, the constellation UIs, and the Collection Runner.

## Goals / Non-Goals

**Goals:**
- A config-selected `S3BlobStore` that satisfies the existing `BlobStore` contract via S3 primitives,
  with the filesystem backend retained for local dev.
- A plain `/v1/...` REST surface that is a thin second adapter over the same core (no duplicated
  domain logic), reusing scoped auth and request-tracing.
- Delete the `apollo-blobs` NFS PVC from the deployment when `BLOB_BACKEND=s3`.

**Non-Goals:**
- Apollo exposing an **S3-compatible server** API (consumers wanting S3 use QuObjects directly).
- GCS/S3 client-library compatibility for the REST surface (plain resource REST only for v1).
- Resumable/chunked REST uploads (single streamed body for v1).
- Putting the REST contract in the-lexicon (kept Apollo-local; services use gRPC).
- Content-addressing change — `BlobRef` stays a random opaque key.

## Decisions

### S3 client: `pekko-connectors-s3` (Alpakka S3)
Use Alpakka S3 rather than the AWS SDK. Rationale: it is Pekko-Streams-native (`S3.multipartUpload` is
a `Sink`, `S3.download`/`getObject` a `Source`), so the existing streaming `put`/`get` code and the
single-pass checksum `Flow` port with minimal change; it supports custom endpoints and **path-style
access** required by S3-compatible stores. Alternative: AWS SDK v2 `S3AsyncClient` (reactive-streams
interop works, but adds an impedance layer over Pekko Streams and more ceremony for path-style/custom
endpoints).

### Multipart upload realizes the atomic-staging contract
The filesystem backend's temp-file → fsync → atomic-rename (design D11) maps onto S3 multipart upload:
parts are invisible until `CompleteMultipartUpload`, and the checksum-mismatch-before-commit rule
(D10) becomes "complete on match, `AbortMultipartUpload` on mismatch." Client-side crc32c+md5 is still
computed in one pass (the catalog stores and returns them); S3 is just the sink. Aborted/incomplete
uploads are the `.tmp` debris analog, enumerated via `ListMultipartUploads` and reclaimed via
`AbortMultipartUpload` — so blob-gc's requirements are unchanged, only its enumeration source moves.
An S3 lifecycle rule MAY additionally auto-expire incomplete uploads (belt-and-suspenders; not
required).

### Key mapping: single S3 bucket, `BlobRef` verbatim as the key
Store all payloads in one S3 bucket, using `BlobRef.value` (`<apolloBucket>/<shard>/<id>`) directly as
the object key. Rationale: `BlobRef` is already an opaque path-shaped string, so no ref-layout change;
one S3 bucket dodges per-bucket S3 limits and keeps credentials/config simple. `listBucketsOnDisk`
becomes a delimiter-`/` prefix listing; `listStoredBlobs(bucket)` a prefix listing. Alternative:
one S3 bucket per Apollo bucket (cleaner `listBuckets`, but multiplies S3 buckets and lifecycle
config) — rejected for v1.

### REST shape: plain resource API at `/v1/...`, second adapter over the core
`PUT/DELETE/GET /v1/buckets/{bucket}`, `PUT/GET/HEAD/DELETE /v1/buckets/{bucket}/objects/{object}`,
`GET /v1/buckets/{bucket}/objects?prefix=&pageToken=`. Uploads/downloads are the raw HTTP entity
(Pekko HTTP streams natively) — dropping gRPC's header+chunk framing, which is simpler for clients.
Metadata rides as `HEAD`/`GET` response headers; bucket/list operations return JSON. The routes call
the same `ObjectService`/entities/`BlobStore` as gRPC — no business logic is duplicated; they only
translate HTTP ↔ domain. Alternatives (GCS JSON-API or S3-compatible) were rejected as heavy or
redundant with QuObjects.

### Reuse existing HTTP cross-cutting
Mount the REST routes on the existing HTTP listener under the `RequestTracing` directive (correlation
IDs + access logs for free) and gate them with the existing `TokenAuthenticator.authorizeHttp(header,
scope)` (write for mutations, read for reads) — the admin route already proves this pattern. No new
auth or tracing machinery.

### Transport security posture rises with REST
Today the HTTP listener is cleartext and carries only health/metrics/admin. REST adds object bytes +
bearer tokens, so the design extends the existing TLS toggle to cover the HTTP listener (or documents
cleartext-REST as a deliberate LAN-only choice per the access model). This is a posture decision, not
new crypto — reuse the `TlsContext` already built for gRPC.

## Risks / Trade-offs

- **QuObjects S3 fidelity is the gating unknown** → a **spike is task 1**: point Alpakka S3 at
  QuObjects and verify multipart upload + `CompleteMultipartUpload`, `ListMultipartUploads` /
  `AbortMultipartUpload`, `ListObjectsV2` (prefix + delimiter), path-style addressing, and
  crc32c/md5 handling. If multipart is unsupported, the streaming-unknown-length path needs a rethink
  (buffer-to-length or a size hint) — decided before building the backend, not after.
- **Orphan between S3-complete and journal-commit** → identical to today's on-disk orphan: the blob is
  written before the `ObjectCommitted` event, so a crash in between leaves an unreferenced S3 object.
  The existing blob-gc reclaims it (now via `ListObjectsV2` vs the catalog). No new consistency model.
- **`delete` "did it exist" semantic** → S3 `DeleteObject` is idempotent and does not report prior
  existence; either accept a weaker boolean or `HEAD` first. Minor; prefer accepting the weaker return.
- **Apollo now holds QuObjects credentials** → new secret surface; supply via env/mounted secrets like
  TLS/auth secrets, never in source/image.
- **QNAP as a bigger blast radius** → Apollo's bytes and (likely) its node both lean on the QNAP. It
  already is the NAS/storage hub; acceptable for a homelab, noted.
- **Combined change is larger** → S3 backend and REST are independent; sequence them within the change
  (spike → S3 backend → REST) so each lands and verifies on its own even though they share one PR.

## Migration Plan

Fully additive and reversible. The filesystem backend stays and remains the default (`BLOB_BACKEND`
unset ⇒ `filesystem`), so existing behavior is unchanged until a deployment opts into `s3`. Rollout:
(1) spike QuObjects; (2) build `S3BlobStore` + config/readiness behind `BLOB_BACKEND`; (3) add REST
routes; (4) in Codex, set `BLOB_BACKEND=s3` + S3 endpoint/credentials and **drop the `apollo-blobs`
NFS PVC** from the chart. Rollback = flip `BLOB_BACKEND` back (data would then diverge, so rollback is
clean only before real data lands on S3). The gRPC contract is untouched throughout.

## Spike result (2026-08-25)

The QuObjects fidelity spike (Alpakka `pekko-connectors-s3` 1.2.0 against the live store) **passed
all checks**: native multipart upload + completion, `getObject`, `ListObjectsV2` (prefix, size,
mtime), `ListMultipartUploads`, path-style addressing, make/list/delete. Two concrete constraints for
the build: (1) pin `pekko-http-xml` to `pekkoHttpVersion` alongside the connector (mixed-version
guard); (2) the QuObjects endpoint uses a **self-signed, hostname-mismatched** TLS cert, so the
backend needs custom client TLS (truststore + hostname handling) — preferably fix the QuObjects cert's
SAN; never ship trust-all. `AbortMultipartUpload`-of-incomplete was not exercised (no incomplete
upload existed) and may need the AWS SDK v2 client directly for the reconciliation path.

## Open Questions

- **QuObjects multipart support** — RESOLVED (spike: native multipart works).
- **QuObjects TLS cert** — fix the cert's SAN on the NAS, or configure a LAN-scoped truststore/loose
  TLS in the backend? Decide at apply time (prefer fixing the cert).
- **S3 checksums** — compute purely client-side (as today) or also request S3-side CRC32C validation?
  Leaning client-side only to preserve exact catalog values.
- **REST error body shape** — a small JSON error envelope (`{code,message,correlationId}`) vs bare
  status; leaning JSON envelope carrying the correlation id.
- **TLS on HTTP now or follow-on** — extend the TLS toggle to the HTTP listener within this change, or
  ship REST LAN-cleartext and do HTTP-TLS separately? Decide at apply time.
