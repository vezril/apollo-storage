# object-api — Spec Delta

The client-facing gRPC surface for the bucket and object lifecycle (design D7),
delegating to the existing `ObjectService`, `BucketEntity`, and `BlobStore`. All
payload transfer is streaming; HTTP `/health` (service-runtime) is unchanged.

## ADDED Requirements

### Requirement: Bucket lifecycle over gRPC

The API SHALL expose `CreateBucket` and `DeleteBucket`, issuing the corresponding
domain commands through the bucket entity and returning a success response or a
status code reflecting the domain outcome.

#### Scenario: Create then delete a bucket
- **WHEN** `CreateBucket("media")` is called on a fresh service
- **THEN** it succeeds, and a subsequent `DeleteBucket("media")` also succeeds

#### Scenario: Edge case — duplicate create is ALREADY_EXISTS
- **GIVEN** bucket `media` exists
- **WHEN** `CreateBucket("media")` is called again
- **THEN** the RPC fails with status `ALREADY_EXISTS`

#### Scenario: Edge case — invalid bucket name is INVALID_ARGUMENT
- **WHEN** `CreateBucket("Invalid_Name")` is called
- **THEN** the RPC fails with status `INVALID_ARGUMENT`

### Requirement: Streaming object upload

`PutObject` SHALL be client-streaming: the first message carries the metadata header
(bucket, object name, content type, and optional expected `crc32c`/`md5`) and each
subsequent message carries a payload chunk. The service SHALL stream the chunks to
the blob store with checksum computation, commit the object, and return the assigned
generation, computed checksums, and byte count. A payload that fails checksum
verification SHALL NOT commit an object.

#### Scenario: Upload commits and returns metadata
- **GIVEN** an existing bucket
- **WHEN** a payload is uploaded via `PutObject` (header then chunks)
- **THEN** the response carries the object's generation, computed checksums, and size,
  and the object is subsequently retrievable

#### Scenario: Edge case — large multi-chunk payload streams without buffering wholly
- **WHEN** a payload larger than one chunk is uploaded
- **THEN** it is stored and read back byte-for-byte identically

#### Scenario: Edge case — checksum mismatch is FAILED_PRECONDITION
- **WHEN** `PutObject` supplies expected checksums that do not match the streamed bytes
- **THEN** the RPC fails with status `FAILED_PRECONDITION` and no object is committed

### Requirement: Streaming object download and metadata

`GetObject` SHALL be server-streaming, emitting a metadata header message followed by
payload chunks for the object's current version. `HeadObject` SHALL return the
object's metadata, checksums, and generation without the payload. A request for a
missing bucket or object SHALL fail with `NOT_FOUND`.

#### Scenario: Download returns the exact bytes
- **GIVEN** a previously uploaded object
- **WHEN** `GetObject` is called for it
- **THEN** the concatenated payload chunks equal the uploaded bytes, and the header
  reports its content type and checksums

#### Scenario: Head returns metadata without payload
- **GIVEN** a previously uploaded object
- **WHEN** `HeadObject` is called
- **THEN** it returns the content type, size, checksums, and generation, and no payload

#### Scenario: Edge case — missing object is NOT_FOUND
- **WHEN** `GetObject` or `HeadObject` is called for an object that does not exist
- **THEN** the RPC fails with status `NOT_FOUND`

### Requirement: Object deletion over gRPC

`DeleteObject` SHALL delete the object through the object service (removing its
payload best-effort) and return success, or `NOT_FOUND` if the object does not exist.

#### Scenario: Delete removes the object
- **GIVEN** a previously uploaded object
- **WHEN** `DeleteObject` is called
- **THEN** it succeeds and a subsequent `HeadObject` returns `NOT_FOUND`

#### Scenario: Edge case — deleting a missing object is NOT_FOUND
- **WHEN** `DeleteObject` is called for an object that does not exist
- **THEN** the RPC fails with status `NOT_FOUND`

### Requirement: gRPC health service

The service SHALL implement the standard `grpc.health.v1.Health` service, reporting
`SERVING` when the service is ready (Postgres journal and blob store available) and
`NOT_SERVING` otherwise, consistent with the HTTP `/health` endpoint (design D7).

#### Scenario: Health reports SERVING when ready
- **GIVEN** the service has started and its dependencies are ready
- **WHEN** a client calls `Health.Check`
- **THEN** the response status is `SERVING`

#### Scenario: Edge case — health reports NOT_SERVING before readiness
- **GIVEN** the service has not yet completed its readiness checks
- **WHEN** a client calls `Health.Check`
- **THEN** the response status is `NOT_SERVING`
