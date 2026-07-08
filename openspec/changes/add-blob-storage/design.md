# Design — add-blob-storage

## Context

The initial milestone recorded object metadata in the event journal but stored no
bytes. Design decision **D3** (archived `add-initial-apollostorage`) mandates that
payloads live outside the journal on a swappable blob store, committed only after
checksum-verified persistence. The domain already models this: `CommitObject`
carries `Checksums(crc32c, md5)` and a `BlobRef`, and `BucketEntity` persists
`ObjectCommitted`. This change fills in the missing backend and the orchestration
that ties byte persistence to the commit. Decision numbering continues the
project log (previous change ended at D8).

## Goals / Non-Goals

**Goals:**
- A `BlobStore` abstraction with a filesystem/NFS-backed implementation that
  streams payloads to a configured root using Pekko Streams.
- Checksum-verified, crash-safe, write-once persistence per object generation.
- Two-phase commit: bytes durable → then `ObjectCommitted` persisted (never the
  reverse).
- Streaming read-back and deletion keyed by `BlobRef`.
- Startup readiness of the store, mirroring the Postgres probe.

**Non-Goals:**
- The client-facing object API (gRPC upload/download, D7) — a later change drives
  this store; here it is an internal, tested service.
- Content-addressed deduplication, orphan-blob garbage collection / reconciliation
  sweeps, encryption at rest, and non-filesystem backends (kept behind the trait).

## Decisions

### D9 — Deterministic, hashed blob layout owned by the store

The blob store owns the `BlobRef`. A blob's relative path is
`<bucket>/<h[0:2]>/<h>/<generation>` where `h = sha256hex(objectName)`. The store
returns this `BlobRef` from `put`; the orchestration then commits it.
- **Why**: deterministic (idempotent retries land on the same path), fixed-length
  components (avoids filesystem path/component-length limits that raw 1 KiB object
  names with many `/` segments could hit), sharded by `h[0:2]` to avoid huge flat
  directories, and correlatable to `(bucket, object, generation)` for future GC.
- **Alternatives**: raw `<bucket>/<objectName>/<generation>` (rejected — path
  length / deep-dir risk, though object names are already traversal-safe);
  content-addressed by payload hash (rejected for v1 — enables dedup but
  complicates deletes/refcounting; deferred).

### D10 — Single-pass streaming checksums, verify-or-record

`crc32c` (`java.util.zip.CRC32C`) and `md5` (`MessageDigest`) are computed by a
Pekko Streams stage as bytes flow to disk (single pass, no re-read). `put` accepts
an optional expected `Checksums`: if supplied and the computed values differ, the
put fails (temp file removed, no commit). If absent, the computed checksums are
recorded on the event.
- **Why**: supports both a verifying caller (a future API passing client-supplied
  digests) and a trusted internal caller, without a second read of the payload.
- **Alternative**: compute after write by re-reading the file (rejected — doubles
  I/O over NFS).

### D11 — Crash-safe writes via temp file + fsync + atomic rename

Bytes are written to `<root>/<bucket>/.tmp/<uuid>`, the file and its parent
directory are fsynced, then `Files.move(…, ATOMIC_MOVE)` promotes it to the final
`BlobRef` path. Failures delete the temp file.
- **Why**: a partially written or torn file is never observable at the committed
  path; atomic rename on one filesystem is POSIX-atomic and works within a single
  NFS export.
- **Alternative**: write in place (rejected — a crash mid-write leaves a corrupt
  committed blob).

### D12 — Order & orphan policy: blob before event, prefer orphans over dangling refs

The payload is promoted to its final path **before** `CommitObject` is sent to the
entity. If the event persist fails afterward, the blob is a harmless orphan
(GC-able later); the reverse — an event with no bytes — is never allowed. On
`ObjectDeleted`, the blob is deleted best-effort; a failed delete leaves an orphan,
never a dangling reference.
- **Why**: an orphan wastes disk (recoverable); a dangling reference is data loss.
- **Trade-off**: orphans accumulate until a future reconciliation sweep exists.

### D13 — Write-once, idempotent generations

Each `(bucket, object, generation)` blob is immutable and write-once. Because the
path is deterministic (D9), a retried `put` for the same generation re-promotes
identical content to the same path (idempotent); the entity’s generation counter
(D2) guarantees a fresh generation for genuinely new content.

### D14 — Startup readiness probe for the store

`BlobStoreReadiness` verifies at startup that the root exists, is a directory, and
is writable (create+delete a probe file), mirroring `PersistenceReadiness`. A
missing or read-only mount surfaces as unhealthy / fast non-zero exit rather than
silent write failure at commit time. Config: `apollostorage.blob.root` ↔
`BLOB_STORE_PATH` (default `/var/lib/apollostorage/objects`, per D8).

## Risks / Trade-offs

- **NFS rename/fsync semantics vary by server/mount options** → Mitigation: atomic
  rename within a single export; fsync file and parent dir; document `hard` NFS
  mounts (already in README); integration-test the store on a local filesystem and
  treat NFS as a same-semantics POSIX mount.
- **Orphan blobs from partial failures / deletes** → Mitigation: accepted by D12;
  correlatable layout (D9) makes a future reconciliation sweep tractable.
- **Checksum CPU on large payloads** → Mitigation: single-pass streaming (D10);
  acceptable at homelab throughput.
- **Path/component length for pathological object names** → Mitigation: fixed-length
  hashed layout (D9).
- **Concurrent puts to the same generation** → Mitigation: deterministic path +
  atomic rename are idempotent; `BucketEntity` serializes commits per bucket (D2).

## Migration Plan

Additive and backward-compatible. New config key defaults to
`/var/lib/apollostorage/objects`; deployments mount writable storage there
(NFS instructions already documented; the compose `objects` volume becomes active).
No journal or schema change. **Rollback**: revert the change — any blobs already on
disk remain but are simply unreferenced by the reverted image.

## Open Questions

- Cadence and trigger for an orphan-blob reconciliation / GC sweep (deferred).
- Whether to re-verify checksums on read (scrubbing) or only on write.
- Whether to expose fsync durability level as a tunable for faster-but-riskier NFS
  setups.
