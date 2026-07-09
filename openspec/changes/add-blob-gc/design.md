## Context

Payloads live in the `FileSystemBlobStore` under `<root>/<bucket>/<id[0:2]>/<id>` (design D9),
with in-progress writes at `<root>/<bucket>/.tmp/<id>`. The authoritative record of a *live*
blob is the bucket entity's state — `BucketState.Active.objects(name).blob` — since a bucket is
an `EventSourcedBehavior` and the object API reads/writes through it (strong consistency, D12).
The read-model `object_index` deliberately does **not** store the `BlobRef` (it carries
crc32c/md5/size for listings), so it is not a source of truth for GC.

Orphans arise because a blob's lifetime is only loosely coupled to its metadata event:
overwrite drops the prior generation's ref from state (`BucketDomain`: `generation.next`,
`objects.updated(...)`); a crash between `blobStore.put` and the `ObjectCommitted` ask leaves a
committed file with no event; and `delete`'s post-event blob removal is best-effort. This change
adds a safe reconciliation and closes the overwrite leak.

## Goals / Non-Goals

**Goals:**
- Reclaim orphaned payloads without ever deleting a live one.
- Stop overwrites from creating orphans in the first place.
- Make the sweep safe by construction: dry-run default, grace period, explicit confirm.

**Non-Goals:**
- Scheduled/automatic sweeps (manual trigger only in v1).
- Post-level dedup / soft-delete-and-purge (Artemis's concern, constellation backlog #6).
- Reclaiming derivatives or reconciling across a backup restore.
- Changing the object API surface or the wire contract.

## Decisions

### D50 — Authoritative live set from the entities, enumerated from disk
The sweep walks the blob root, treats each top-level directory as a bucket that *has* blobs,
and asks **that bucket's entity** for its live `BlobRef`s (a new strongly-consistent read-only
query). Orphans are computed per bucket: `on-disk − live`. Rationale: the entity is the source
of truth (no projection lag, no read-model schema change), and enumerating buckets from disk
means we only query buckets that actually have payloads. Alternatives: query the read model
(lacks `BlobRef`, and lags — unsafe); replay the journal (heavy).

### D51 — Blob-store enumeration at the FS boundary
`BlobStore` gains `listBlobs(bucket): Source/Iterator[(BlobRef, Instant modifiedAt)]` and a
listing of `.tmp` debris with ages, implemented in `FileSystemBlobStore` by walking the sharded
layout. Age comes from the file's last-modified time. Streaming/iterator-based so a large store
does not load every ref into memory at once.

### D52 — Reconciliation is pure core logic
The orphan decision is a pure function in `core` (no Pekko, no I/O):
`orphans(onDisk: Set[(BlobRef, Instant)], live: Set[BlobRef], now, grace) = onDisk where ref ∉ live && age(ref) ≥ grace`. Exhaustively unit-tested (live kept, unknown-and-old reclaimed,
unknown-but-recent spared, `.tmp` debris aged out). Keeping the policy pure makes the safety
rules test-provable independent of the filesystem.

### D53 — Grace period is the core safety mechanism
Only blobs whose last-modified age is **≥ a configurable grace period** (default 24h,
`BLOB_GC_GRACE`) are eligible. This protects the two windows a strongly-consistent live-set
can't: a blob persisted microseconds before its `ObjectCommitted` (crash-window candidate that
may still be committing), and any freshly written file. Projection lag is irrelevant (we don't
use the read model), but the grace period still gives a wide margin. Recent `.tmp` files are
spared for the same reason; old ones are debris.

### D54 — Dry-run by default; deletion is explicit and best-effort
A sweep computes and **reports** `{scanned, live, orphaned, bytesOrphaned, reclaimed,
bytesReclaimed, tmpReclaimed}` without deleting. Actual reclamation runs only when the caller
explicitly confirms (`delete=true`), deletes best-effort (a failed unlink is logged and counted,
never fatal), and re-reports. Rationale: GC is destructive; the default must be observe-only,
and a human opts into deletion after reading the report.

### D55 — Close the overwrite leak at the source
`ObjectService.commit`, on a successful commit that **superseded** a prior generation (the
pre-commit `GetObject` returned an entry with a different `BlobRef`), deletes the superseded blob
best-effort — exactly the pattern `delete` already uses. This removes the dominant steady-state
orphan source; the sweep then only mops up crash debris and failed deletes. Belt-and-suspenders:
if the reclaim fails, the sweep still catches it later.

### D56 — Admin-triggered via a guarded endpoint
The sweep is exposed as an admin HTTP endpoint (`POST /admin/blob-gc`, dry-run unless the
request explicitly confirms deletion), enabled by config and **gated by API auth when auth is
enabled**. It runs in-process so it has the cluster (entity queries) and the blob store to hand,
and returns the report as JSON. Manual trigger only. Alternative (a one-shot CLI mode of `Main`)
was considered — cleaner blast radius but it can't ask the running cluster's entities without
forming its own; deferred as a future runner. A scheduled trigger is explicitly future work.

## Risks / Trade-offs

- **Deleting a live blob** → the live set is strongly consistent (from the entity), and the
  grace period covers the persist→commit window; a blob is reclaimed only if it is unknown to
  its bucket's entity *and* older than the grace period.
- **A destructive admin endpoint is a foot-gun** → dry-run default, explicit delete confirmation,
  auth-gated, off unless enabled, and it reports before it reclaims.
- **Reclamation outrunning backups** → documented invariant: backup retention MUST outlive the
  grace period + sweep cadence, so a reclaimed blob was already backed up. Surfaced in the README.
- **A bucket entity is unreachable mid-sweep** → that bucket is skipped (reported), never swept
  blind — an error assembling a bucket's live set aborts *that bucket*, not a delete.
- **Huge store** → streaming enumeration; the sweep reports counts, not a full in-memory listing.

## Migration Plan

Additive — no schema or data migration, no API change. Deploy normally; the endpoint is
opt-in and dry-run by default. First real use: run the sweep in dry-run, read the report, then
re-run with delete confirmation. Rollback: disable the endpoint (config) or simply never call
it; the overwrite-reclaim is a pure best-effort improvement with no rollback concern.

## Open Questions

- Grace-period default — 24h proposed; confirm against the intended backup cadence.
- Whether the sweep should also verify a candidate's checksum before deleting (leaning no —
  identity is by `BlobRef`, and reading every candidate is expensive).
- Whether to add a scheduled trigger now or leave it manual (leaning manual for v1, per the
  reprocessing precedent).
