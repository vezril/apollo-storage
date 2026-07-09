## Why

Object payloads can be left on disk with no live object referencing them — **orphans** that
accumulate and waste storage with no way to reclaim them. Three sources exist today, all
acknowledged in the blob-storage design as deferred:

1. **Overwrite** — committing a new generation over an existing object name replaces the
   entry (`generation.next`, new `BlobRef`); the superseded generation's blob is never deleted.
2. **Crash between persist and commit** — a payload is made durable *before* its
   `ObjectCommitted` event (design D12); if the process dies in between, the blob is on disk
   with no event.
3. **Failed best-effort delete** — `delete` removes the blob only after `ObjectDeleted`; a
   failed blob delete is logged as an orphan and left behind.

Plus `.tmp` debris from failed/aborted writes. The correlatable `BlobRef` layout
(`<bucket>/<id[0:2]>/<id>`, D9) makes reconciling disk against live state tractable — this
change makes that reconciliation real, safely.

## What Changes

- **Close the biggest leak at the source**: `ObjectService.commit` reclaims the **superseded
  blob** (best-effort, mirroring `delete`) when an overwrite creates a new generation, so
  overwrites stop orphaning.
- **Reconciliation sweep** (new `blob-gc` capability): enumerate stored blobs, diff against the
  **authoritative live set** (queried from the bucket entities — strongly consistent), and
  reclaim orphans **older than a configurable grace period**, plus stale `.tmp` debris. The
  sweep is **dry-run by default** — it reports candidates; actual deletion requires explicit
  confirmation. Reclamation is best-effort, logged, and counted.
- **Admin trigger**: a guarded admin endpoint runs the sweep on demand and returns a report
  (scanned / orphaned / reclaimed / bytes). Manual trigger only; protected by API auth when
  enabled.

## Capabilities

### New Capabilities
- `blob-gc`: reconcile the blob store against live object state and reclaim orphaned payloads
  (grace-period-guarded, dry-run-by-default), on an admin-triggered sweep.

### Modified Capabilities
- `blob-storage`: the store gains the ability to **enumerate** stored blobs (with age) and
  `.tmp` debris for reconciliation; `ObjectService` reclaims the superseded blob on overwrite.
- `event-persistence`: the bucket entity gains a **read-only query** for its live `BlobRef`s,
  so the sweep can assemble the authoritative live set strongly-consistently.

## Impact

- **Code**: `BlobStore`/`FileSystemBlobStore` (enumerate + age); `ObjectService` (overwrite
  reclaim); `BucketEntity` (live-refs query); a pure reconciliation core (orphan =
  on-disk − live, filtered by grace period); the sweep runner + admin endpoint; config for
  the grace period + enablement.
- **Safety**: dry-run default, grace period (protects in-flight commits and recent writes),
  best-effort deletes, an explicit confirmation to delete, and a documented invariant that
  **backup retention must outlive reclamation**. No change to the object API surface or the
  wire contract.
- **Out of scope**: automatic/scheduled sweeps (manual trigger only for v1); post-level md5
  dedup / soft-delete-and-purge (that lives in Artemis); reclaiming across a restore.
