# Tasks — add-blob-storage

TDD is non-negotiable: every implementation task is preceded by a failing-test
task and followed by a refactor + run-tests task. Do not start an implementation
task while its tests are green (that means the tests are wrong) or missing.

Payloads live in the `server` module (Pekko Streams); the pure `core` domain
(`BlobRef`, `Checksums`, `CommitObject`, `DeleteObject`) is unchanged.

## 1. BlobStore abstraction & filesystem persistence

- [ ] 1.1 **Red**: streaming-put tests — payload streams to disk, `crc32c`+`md5` computed single-pass match a fresh digest, returned `BlobRef` addresses the bytes, byte count correct (edge cases: empty payload, large multi-chunk payload)
- [ ] 1.2 **Green**: `BlobStore` trait + `FileSystemBlobStore.put` with an inline streaming checksum stage
- [ ] 1.3 **Red**: checksum-verification tests — expected `Checksums` mismatch ⇒ failure, no blob at final path, temp file removed (edge case, design D10)
- [ ] 1.4 **Green**: optional expected-checksum verification + failure cleanup
- [ ] 1.5 **Red**: crash-safety tests — committed path appears only via atomic rename; a simulated mid-stream failure leaves no committed file and no leftover temp (edge cases, design D11)
- [ ] 1.6 **Green**: temp-file + fsync (file and parent dir) + `ATOMIC_MOVE` write path
- [ ] 1.7 **Refactor**: extract the checksum stage / write pipeline; run the store suite

## 2. Deterministic layout, configuration, readiness

- [ ] 2.1 **Red**: layout tests — `BlobRef` for `(bucket, object, generation)` is stable across derivations, sharded by `sha256` prefix; re-put of the same generation is idempotent (edge cases, design D9/D13)
- [ ] 2.2 **Green**: deterministic `BlobRef` derivation (`<bucket>/<h[0:2]>/<h>/<generation>`) + write-once semantics
- [ ] 2.3 **Red**: config tests — `apollostorage.blob.root` default and `BLOB_STORE_PATH` override precedence (edge case, design D8/D14)
- [ ] 2.4 **Green**: blob-root config in `AppConfig` + `application.conf`
- [ ] 2.5 **Red**: `BlobStoreReadiness` tests — writable root passes; missing or read-only root fails fast with the path named (edge cases, design D14)
- [ ] 2.6 **Green**: `BlobStoreReadiness` probe; wire into `Main` alongside the Postgres probe

## 3. Two-phase object commit

- [ ] 3.1 **Red**: `ObjectService.commit` tests (`EventSourcedBehaviorTestKit` + temp blob root) — bytes persisted, then `ObjectCommitted` carries the returned `BlobRef` + verified checksums; blob present at the ref (edge cases)
- [ ] 3.2 **Green**: `ObjectService.commit` — put blob, then send `CommitObject` to `BucketEntity` (design D12 ordering)
- [ ] 3.3 **Red**: failure-ordering tests — payload write failure ⇒ no `CommitObject`, zero events, no blob on disk (edge case: no event without a blob)
- [ ] 3.4 **Green**: enforce blob-before-event ordering and error propagation

## 4. Read-back & deletion

- [ ] 4.1 **Red**: read tests — `get(BlobRef)` streams a byte-identical payload; unknown ref ⇒ typed not-found, no partial/empty stream (edge cases)
- [ ] 4.2 **Green**: `FileSystemBlobStore.get`
- [ ] 4.3 **Red**: delete tests — `ObjectService.delete` persists `ObjectDeleted` and removes the blob; a failed blob delete leaves an orphan, is logged, and never dangles a committed object (edge cases, design D12)
- [ ] 4.4 **Green**: `ObjectService.delete` + best-effort blob delete
- [ ] 4.5 **Refactor**: dedupe path/ref logic; run the full unit suite

## 5. Integration, runtime wiring & docs

- [ ] 5.1 **Red**: testcontainers integration — a full commit through real Postgres + a temp blob root persists an event with the correct `BlobRef`/checksums and readable bytes; after entity restart the recovered object's blob is still readable (edge cases)
- [ ] 5.2 **Green**: wire `BlobStore` + `ObjectService` into the runtime with config
- [ ] 5.3 Update `README.md` + `docker-compose.yml`: activate the `objects` volume usage, document `BLOB_STORE_PATH`, and note orphan-blob GC is a future change
- [ ] 5.4 **Refactor + verify**: run full unit + integration suites, `scalafmtCheckAll`, and confirm a deployment with a mounted blob root stays healthy
