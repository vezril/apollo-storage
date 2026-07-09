# object-api

The client-facing gRPC surface for the bucket and object lifecycle (design D7),
delegating to the existing `ObjectService`, `BucketEntity`, and `BlobStore`. All
payload transfer is streaming; HTTP `/health` (service-runtime) is unchanged.

## Requirements

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

### Requirement: List buckets

The API SHALL expose `ListBuckets` returning the known bucket names from the read
model, ordered by name, with keyset pagination (`page_size` and a `page_token`
that is the last name of the previous page; the response returns the next
`page_token`, empty when the listing is exhausted).

#### Scenario: Buckets are listed in order
- **GIVEN** buckets `alpha`, `beta`, and `gamma` exist and have propagated to the read model
- **WHEN** `ListBuckets` is called
- **THEN** it returns `alpha`, `beta`, `gamma` in that order

#### Scenario: Edge case — pagination via page_token
- **GIVEN** more buckets than one page holds
- **WHEN** `ListBuckets` is called with a `page_size` smaller than the total
- **THEN** the first response returns that many names and a non-empty `page_token`,
  and calling again with that token returns the next page

### Requirement: List objects by prefix

The API SHALL expose `ListObjects(bucket, prefix, page_size, page_token)` returning
the objects of a bucket whose keys start with `prefix`, ordered by key, with keyset
pagination. Each entry carries the object's key, generation, size, content type,
and checksums. Deleted objects SHALL NOT appear. A missing bucket SHALL fail with
`NOT_FOUND`.

#### Scenario: Objects are listed by prefix
- **GIVEN** a bucket with keys `photos/a.jpg`, `photos/b.jpg`, and `docs/x.txt` in the read model
- **WHEN** `ListObjects` is called with prefix `photos/`
- **THEN** it returns `photos/a.jpg` and `photos/b.jpg`, ordered, and not `docs/x.txt`

#### Scenario: Edge case — deleted objects are absent
- **GIVEN** an object that was listed and is then deleted
- **WHEN** `ListObjects` is called after the deletion propagates
- **THEN** the deleted key is no longer returned

#### Scenario: Edge case — listing a missing bucket
- **WHEN** `ListObjects` is called for a bucket that does not exist
- **THEN** the RPC fails with status `NOT_FOUND`

### Requirement: Authenticated access to object operations

The bucket and object RPCs SHALL require, when authentication is enabled, a valid credential
**whose scope covers the operation** — failing a missing/unknown credential with `UNAUTHENTICATED`
and an insufficiently-scoped one with `PERMISSION_DENIED`, before any bucket or object is touched.
The **read** operations (`GetObject`, `HeadObject`, `ListBuckets`, `ListObjects`) require `read`
scope; the **write** operations (`CreateBucket`, `DeleteBucket`, `PutObject`, `DeleteObject`)
require `write` scope. The `grpc.health.v1.Health` service remains reachable without a credential.

#### Scenario: Unauthenticated object call is rejected before side effects
- **GIVEN** authentication enabled
- **WHEN** `PutObject` is streamed without a valid bearer token
- **THEN** the RPC fails with `UNAUTHENTICATED` and no object is committed

#### Scenario: A read-scoped token is refused a write RPC
- **GIVEN** authentication enabled and a `read`-scoped token
- **WHEN** it calls `DeleteObject`
- **THEN** the RPC fails with `PERMISSION_DENIED` and nothing is deleted

#### Scenario: A read-scoped token may call read RPCs
- **GIVEN** a `read`-scoped token
- **WHEN** it calls `GetObject` or `ListObjects`
- **THEN** the RPC is authorized

#### Scenario: Health is exempt from authentication
- **GIVEN** authentication enabled
- **WHEN** the health service is queried without a token
- **THEN** it responds normally

### Requirement: gRPC contract sourced from the Lexicon

The Apollo object-storage gRPC service definition SHALL be sourced from the shared Lexicon
artifact — a single, versioned source of truth — rather than a repo-local `.proto`. The
server SHALL generate its service stubs from a **pinned** Lexicon version, preserving the
`apollostorage.grpc` protobuf package so the API surface (RPC methods, messages, and status
mappings) is identical to the previous local definition. A version mismatch between the
server and the pinned contract SHALL surface as a build error, not a runtime failure.

#### Scenario: Server builds against the pinned Lexicon contract
- **GIVEN** the Apollo gRPC service published in the Lexicon at a pinned version
- **WHEN** the server module builds
- **THEN** it generates and implements the service from that artifact, with no repo-local
  Apollo `.proto` present

#### Scenario: The API surface is unchanged by the move
- **GIVEN** the existing gRPC test suites (unary, streaming, listing, TLS + auth)
- **WHEN** they run against the Lexicon-generated stubs
- **THEN** they pass unchanged — the RPC methods and message shapes are identical, proving the
  move is not a redesign

#### Scenario: Edge case — an incompatible contract fails the build
- **GIVEN** the server pinned to a Lexicon version incompatible with its implementation
- **WHEN** the module is compiled
- **THEN** the incompatibility is a build/type error, not a runtime surprise
