# Change: add-s3-backend-and-rest-api

## Why

The QNAP NAS already exposes an S3-compatible object store (QuObjects), which is durable,
RAID-backed, and appliance-managed — so Apollo maintaining object **bytes** on its own filesystem/NFS
volume is redundant (and is exactly what has blocked deployment: the `apollo-blobs` NFS PVC). Apollo's
real, non-redundant value is the **event-sourced catalog + gRPC front door** that drives the
constellation (a commit is a domain event Muses/Argus/Hephaestus react to). This change distills Apollo
to that role: it keeps the catalog and delegates durable byte storage to QuObjects over S3. Separately,
the object API is gRPC-only today, which is awkward for browsers, tooling, and the constellation UIs;
this adds a plain REST surface alongside gRPC so humans and simple clients can use Apollo directly.

## What Changes

- **S3/QuObjects blob backend** — a new `S3BlobStore` implementing the existing `BlobStore` trait,
  **selected by config** (`BLOB_BACKEND=filesystem|s3`); the filesystem backend is retained for local
  development. Backed by `pekko-connectors-s3` (Alpakka), path-style addressing, custom endpoint, and
  access-key/secret supplied **as secrets**.
  - Multipart upload realizes the atomic-staging contract (parts invisible until
    `CompleteMultipartUpload`; abort on checksum mismatch) — replacing temp-file→fsync→rename.
  - `ListMultipartUploads`/`AbortMultipartUpload` realize temp-artifact reconciliation;
    `ListObjectsV2` realizes stored-payload enumeration; `HeadBucket` realizes startup readiness.
  - Client-side crc32c+md5 single-pass digest is retained (the catalog stores and returns them).
- **REST object API** — a plain resource API at `/v1/buckets/...` on the HTTP listener, a **second
  adapter over the same core** (`ObjectService` + bucket entities + `BlobStore`) — no domain logic
  duplicated. Raw request/response bodies for streaming up/download; object metadata as `HEAD`
  response headers; JSON for bucket/list operations; checksums as headers. Reuses the existing scoped
  bearer auth (`authorizeHttp`) and the request-tracing directive (correlation IDs + access logs come
  for free). An Apollo-local OpenAPI document describes it.
- **Deployment simplification** — with `BLOB_BACKEND=s3`, Apollo's StatefulSet no longer needs the
  `apollo-blobs` NFS PVC; only the small local-path Postgres journal volume remains.
- **NOT included / later:** S3-compatible *server* API on Apollo (consumers wanting S3 use QuObjects
  directly); resumable/multipart REST uploads (single streamed body for v1); the REST contract living
  in the-lexicon (kept Apollo-local — REST is an edge convenience, services use gRPC).

## Capabilities

### New Capabilities

- `s3-blob-store`: an S3-compatible (`QuObjects`) `BlobStore` backend that satisfies the blob-storage
  contract via S3 primitives (multipart-upload atomicity, list-based enumeration/reconciliation,
  bucket-head readiness), selected at runtime, with its endpoint/credentials configuration.
- `rest-object-api`: a plain RESTful HTTP surface for bucket and object operations at `/v1/...`,
  mirroring the gRPC object API over the same application core, with streaming bodies, scoped auth,
  and an Apollo-local OpenAPI description.

### Modified Capabilities

- `blob-storage`: the "Configurable store root with environment override" requirement broadens to
  **configurable storage backend** — `BLOB_BACKEND` selects filesystem or S3; the filesystem root
  remains the filesystem-backend setting.

## Impact

- **New code**: `S3BlobStore` (+ Alpakka S3 wiring, config, credentials), REST routes + JSON codecs +
  OpenAPI doc, backend-selection wiring in `Main`, an S3 readiness check.
- **Touched code**: `AppConfig` (backend selector + S3 section), `Main` (choose backend; mount REST
  routes under the traced/authed HTTP server), `BlobStoreReadiness` (S3 variant), `BlobGc`
  reconciliation adapts to S3 enumeration (requirements unchanged), `build.sbt`
  (`pekko-connectors-s3`).
- **Config/secrets**: new `BLOB_BACKEND`, `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_PATH_STYLE`,
  and S3 credentials as secrets (fits the existing TLS/auth-secrets pattern). Apollo now holds
  QuObjects credentials.
- **Security**: REST carries object bytes + bearer tokens on the HTTP listener; the design must extend
  the TLS posture to the HTTP listener (or scope cleartext REST to LAN-only per the access model).
- **Deployment (Codex)**: the Apollo HelmRelease/chart drops the `apollo-blobs` NFS PVC and gains S3
  endpoint/credentials config when `BLOB_BACKEND=s3`; supersedes the NFS-for-blobs storage path.
- **Risk/unknown (spike-gated)**: QuObjects' S3 fidelity (multipart, list-multipart, ListObjectsV2,
  path-style, checksums) is verified by a spike before the backend is built.
- **Depends on**: the shipped `blob-storage`, `object-api`, `api-security`, and `request-tracing`
  capabilities. No breaking change to the gRPC contract.
