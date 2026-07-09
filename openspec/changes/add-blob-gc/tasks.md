# Tasks — add-blob-gc

TDD is non-negotiable: every implementation task is preceded by a failing-test task and
followed by a refactor + run-tests task. The sweep is **dry-run by default**; tests exercise
both the report-only and the confirmed-delete paths.

## 1. Live blob-refs query (event-persistence)

- [x] 1.1 **Red**: `BucketEntity` test — a read-only `GetLiveBlobRefs` query returns exactly the
  live generations' `BlobRef`s for an active bucket, an empty set for an empty/deleted bucket,
  and persists no event (design D50)
- [x] 1.2 **Green**: add the `GetLiveBlobRefs` read-only command + handler on `BucketEntity`

## 2. Blob-store enumeration (blob-storage)

- [x] 2.1 **Red**: `FileSystemBlobStore` tests — enumerating a bucket yields each stored
  `BlobRef` with its last-modified time; `.tmp` artifacts are enumerable separately with ages
  (design D51)
- [x] 2.2 **Green**: implement streaming enumeration over the sharded layout + `.tmp` listing

## 3. Reclaim the superseded blob on overwrite (blob-storage)

- [x] 3.1 **Red**: `ObjectService` test — overwriting an object deletes the prior generation's
  blob best-effort; a failed reclaim still commits and logs an orphan (design D55)
- [x] 3.2 **Green**: in `commit`, capture the pre-commit entry and, when the new generation
  supersedes a different `BlobRef`, delete the superseded blob best-effort

## 4. Reconciliation core (pure)

- [x] 4.1 **Red**: `core` unit tests for the pure orphan policy — live refs kept;
  unreferenced-and-old reclaimable; unreferenced-but-recent spared; stale `.tmp` reclaimable
  (design D52/D53)
- [x] 4.2 **Green**: pure `BlobReconciliation` in `core` (no Pekko/IO): `orphans(onDisk, live,
  now, grace)`

## 5. Sweep runner + report (blob-gc)

- [x] 5.1 **Red**: sweep test (single-node cluster + temp blob root) — seed live objects +
  orphans (superseded, crash-style, `.tmp`); a dry-run reports the right counts and deletes
  nothing; a confirmed sweep reclaims only the aged orphans and reports bytes; a failed unlink
  is counted, not fatal (design D54)
- [x] 5.2 **Green**: `BlobGc` runner — per-bucket (enumerate buckets from disk), ask
  `GetLiveBlobRefs`, apply the core policy, report; delete best-effort only when confirmed

## 6. Admin trigger + config (blob-gc)

- [x] 6.1 Config in `AppConfig` + `application.conf`: `apollostorage.blob-gc.{enabled (default
  false), grace}` (env `BLOB_GC_ENABLED`, `BLOB_GC_GRACE`)
- [x] 6.2 **Red**: route test — `POST /admin/blob-gc` returns the report as JSON; dry-run
  unless deletion is explicitly confirmed; `404` when disabled; requires auth when auth is on
  (design D56)
- [x] 6.3 **Green**: the admin route running the sweep, wired in `Main` behind the auth
  guard + enablement flag

## 7. Docs & verification

- [x] 7.1 README "Reclaiming orphaned storage": what an orphan is, the dry-run→confirm flow, the
  grace period, and the **backup-retention-outlives-reclamation** invariant; document
  `BLOB_GC_ENABLED` / `BLOB_GC_GRACE` in the Configuration table
- [x] 7.2 **Verify**: on a running container, create/overwrite/delete objects to produce
  orphans, run the dry-run sweep (counts match), then a confirmed sweep (orphans gone, live
  objects still served)
- [x] 7.3 **Refactor + verify**: full unit + integration suites + `scalafmtCheckAll`
