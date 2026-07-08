# blob-storage — Spec Delta

Object payloads live outside the event journal (design D3) in a `BlobStore`. This
capability defines the filesystem/NFS-backed store and the orchestration that ties
byte persistence to the existing `CommitObject`/`DeleteObject` domain commands. All
byte movement is streaming (Pekko Streams); the store owns the `BlobRef` layout.

## ADDED Requirements

### Requirement: Streaming, checksum-verified payload persistence

The store SHALL persist an object payload by streaming it to disk while computing
`Checksums(crc32c, md5)` in a single pass, returning the resulting `BlobRef`, byte
count, and checksums. When a caller supplies expected checksums, a mismatch SHALL
fail the operation, persist no blob at the committed path, and produce no event.

#### Scenario: Payload stored and checksums computed
- **WHEN** a payload is put for `(bucket, object, generation)` with no expected checksums
- **THEN** the bytes are written under the store root, the returned `Checksums` match a
  fresh crc32c/md5 of the payload, and the returned `BlobRef` addresses the stored bytes

#### Scenario: Edge case — checksum mismatch rejects the write
- **WHEN** a payload is put with an expected `Checksums` that does not match the streamed bytes
- **THEN** the operation fails, no blob exists at the committed `BlobRef` path, and no
  `ObjectCommitted` event is persisted

### Requirement: Crash-safe atomic writes

Payload writes SHALL be crash-safe: bytes are written to a temporary file, flushed,
and atomically renamed to the final `BlobRef` path. A partially written payload
SHALL never be observable at the committed path.

#### Scenario: Successful write is atomically promoted
- **WHEN** a payload finishes streaming successfully
- **THEN** the final `BlobRef` path appears only after a complete, atomic rename (no
  temporary or partial file remains at the committed path)

#### Scenario: Edge case — failed write leaves no committed blob
- **WHEN** a payload stream fails partway (I/O error or checksum mismatch)
- **THEN** no file exists at the final `BlobRef` path and the temporary file is removed

### Requirement: Two-phase object commit

An object commit SHALL persist the payload durably **before** `CommitObject` is sent
to the `BucketEntity`, so an `ObjectCommitted` event is never persisted without its
bytes on disk. A failure after the blob is written but before the event is persisted
SHALL leave at most an orphaned blob — never an event without a payload.

#### Scenario: Commit persists bytes then event
- **WHEN** an object is committed through the object service
- **THEN** the blob is present at its `BlobRef` and the `BucketEntity` has persisted an
  `ObjectCommitted` carrying that `BlobRef` and the verified checksums

#### Scenario: Edge case — no event without a blob
- **GIVEN** the payload write fails
- **THEN** no `CommitObject` command is sent and no `ObjectCommitted` event is persisted

### Requirement: Streaming read-back

The store SHALL return the exact bytes of a stored payload as a stream given its
`BlobRef`. A `BlobRef` with no stored payload SHALL yield a typed not-found result,
not an empty or partial stream.

#### Scenario: Payload read back matches what was written
- **GIVEN** a payload previously stored at a `BlobRef`
- **WHEN** it is read by that `BlobRef`
- **THEN** the streamed bytes are byte-for-byte identical to the original payload

#### Scenario: Edge case — unknown blob reference
- **WHEN** a read is requested for a `BlobRef` that was never stored
- **THEN** the result is a typed not-found, and no partial or empty stream is returned

### Requirement: Payload deletion without dangling references

On object deletion the store SHALL remove the payload best-effort. A failed payload
delete SHALL leave an orphaned blob, never a committed object whose bytes were
removed out from under it.

#### Scenario: Deleting an object removes its payload
- **GIVEN** a committed object with a stored payload
- **WHEN** the object is deleted through the object service
- **THEN** the `ObjectDeleted` event is persisted and the payload no longer exists at its `BlobRef`

#### Scenario: Edge case — delete failure does not dangle a reference
- **WHEN** the payload delete fails after `ObjectDeleted` is already persisted
- **THEN** the failure is logged and the blob is treated as an orphan (no partially
  deleted object that still claims present bytes)

### Requirement: Opaque, immutable blob references

The store SHALL assign each stored payload an opaque `BlobRef` at write time and
SHALL never mutate a stored blob's bytes. Reads and deletes SHALL address blobs
solely by that `BlobRef`, which SHALL NOT require knowledge of the object's
generation (the aggregate assigns the generation only at commit time, after the
blob is written).

#### Scenario: Store assigns a usable reference on write
- **WHEN** a payload is put
- **THEN** the store returns an opaque `BlobRef` that reads back the same bytes

#### Scenario: Edge case — a new commit writes a distinct blob
- **GIVEN** an object committed at generation N with a stored blob
- **WHEN** a new payload is committed for the same object (generation N+1)
- **THEN** it is stored under a distinct `BlobRef` and the generation-N blob is left
  intact (superseded, not overwritten)

### Requirement: Configurable store root with environment override

The blob-store root directory SHALL be configured via HOCON key
`apollostorage.blob.root`, overridable by the `BLOB_STORE_PATH` environment variable
(default `/var/lib/apollostorage/objects`); no path is hard-coded in source.

#### Scenario: Environment override takes precedence
- **GIVEN** the HOCON default and a `BLOB_STORE_PATH` environment value
- **WHEN** configuration is resolved
- **THEN** the store uses the environment-specified root, not the default

### Requirement: Startup readiness of the blob store

At startup the service SHALL verify the store root exists, is a directory, and is
writable. A missing or read-only root SHALL surface as unhealthy or a fast non-zero
exit, so a misconfigured mount fails visibly rather than dropping writes at commit time.

#### Scenario: Writable root passes readiness
- **GIVEN** a store root that exists and is writable
- **WHEN** the readiness check runs
- **THEN** it succeeds and the service reports ready

#### Scenario: Edge case — missing or read-only root fails fast
- **GIVEN** a store root that does not exist or is not writable
- **WHEN** the readiness check runs
- **THEN** it fails with a clear error naming the path, and the service reports `DOWN`
  or exits non-zero (no silent write loss later)
