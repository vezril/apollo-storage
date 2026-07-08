## Why

ApolloStorage persists object *metadata* to the event journal but has nowhere to
put the object *bytes* — design decision D3 deliberately keeps payloads out of
the journal on a swappable blob store, and the README already documents mounting
NFS storage into the container for exactly this. This change implements that blob
store so an object commit actually durably stores its payload, closing the gap
between "metadata recorded" and "bytes safe on disk".

## What Changes

- Introduce a **`BlobStore`** abstraction and a **filesystem-backed
  implementation** that streams object bytes (Pekko Streams) to a configured
  root directory — the NFS mount in a homelab deployment.
- Implement the **two-phase object commit** (D3): stream the payload to the blob
  store, compute and verify `Checksums(crc32c, md5)` as bytes flow, and only then
  send `CommitObject` to the `BucketEntity` so `ObjectCommitted` is persisted.
  A checksum mismatch or write failure persists **no** event and leaves **no**
  orphaned blob.
- Support **streaming read-back** and **deletion** of object payloads, keyed by
  the domain's existing `BlobRef`.
- Make writes **crash-safe** (write to a temp file, fsync, atomic rename) so a
  partial write can never be observed as a committed object.
- Add a **blob-store startup readiness check** (mirroring the existing Postgres
  probe) so a missing or read-only mount surfaces as unhealthy / fast exit rather
  than silent data loss.
- Externalize the blob-store root via **HOCON + environment override**
  (`apollostorage.blob.root` ↔ `BLOB_STORE_PATH`, default
  `/var/lib/apollostorage/objects`).

Out of scope (future changes): the client-facing object API (gRPC upload/download
per D7) that will *drive* this store, read-side projections, and content-addressed
deduplication. This change provides the internal, tested storage backend and
commit orchestration that the API layer will call.

## Capabilities

### New Capabilities
- `blob-storage`: filesystem/NFS-backed, checksum-verified streaming persistence
  of object payloads outside the journal, with atomic two-phase commit into the
  event log, streaming read-back, deletion, and startup readiness of the store.

### Modified Capabilities
<!-- None. The domain model already defines BlobRef, Checksums, CommitObject, and
     DeleteObject; the event journal is unchanged. This change adds a new backend
     capability and the orchestration that ties blob persistence to the existing
     commit command — no existing spec requirements change. -->

## Impact

- **Affected code (`server` module)**: new `BlobStore` trait + `FileSystemBlobStore`
  (Pekko Streams, temp-file + atomic-rename writes); an `ObjectService`
  orchestrating put→verify→commit and get/delete against the existing
  `BucketEntity`; a `BlobStoreReadiness` startup probe; blob-root config in
  `AppConfig` and `application.conf`.
- **Configuration**: new key `apollostorage.blob.root` (env `BLOB_STORE_PATH`),
  wired into the readiness check and the runtime.
- **Deployment**: the container must mount writable storage at the blob root
  (NFS instructions already in the README/compose); the compose example's
  commented `objects` volume becomes active usage.
- **Dependencies**: none new — Pekko Streams is already present; checksums use the
  JDK (`java.util.zip.CRC32C`, `java.security.MessageDigest` for MD5).
- **Tests**: streaming put/get round-trip; checksum-mismatch rejection (no event,
  no orphan blob); crash-safety (no partial file visible); delete; readiness when
  the mount is missing or read-only. Integration continues to use testcontainers
  Postgres plus a temp directory as the blob root.
