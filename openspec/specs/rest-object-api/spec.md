# rest-object-api

## Purpose

Apollo serves a plain RESTful bucket/object API at `/v1/...` on the HTTP listener alongside the gRPC
`ObjectApi` — a second adapter over the same event-sourced core (`ObjectService` + bucket entities +
`BlobStore` + read model), so REST and gRPC share generations, checksums, and effects. It is the easy
path for browsers, tooling, and `curl`: uploads/downloads stream the raw HTTP body, object metadata
rides as `X-Apollo-*` response headers, and bucket/list operations return JSON. Scoped bearer auth and
the request-tracing correlation id apply. The surface is described by an Apollo-local OpenAPI document.
## Requirements
### Requirement: RESTful bucket lifecycle
The service SHALL expose bucket lifecycle over REST at `/v1/buckets`: creating a bucket
(`PUT /v1/buckets/{bucket}`), deleting a bucket (`DELETE /v1/buckets/{bucket}`), and listing buckets
(`GET /v1/buckets`, JSON). These operate over the same domain core as the gRPC API, so the same
validation and event-sourced effects apply.

#### Scenario: Create and list a bucket over REST
- **WHEN** a client PUTs `/v1/buckets/{bucket}` then GETs `/v1/buckets`
- **THEN** the bucket is created via the same domain command and appears in the JSON listing

### Requirement: Streaming object upload and download over HTTP
The service SHALL accept an object upload as the raw request body of
`PUT /v1/buckets/{bucket}/objects/{object}` (content type from the `Content-Type` header) and SHALL
return the stored payload as the raw response body of `GET /v1/buckets/{bucket}/objects/{object}`,
streaming in both directions without buffering the whole payload in memory.

#### Scenario: Round-trip an object body
- **WHEN** a client PUTs bytes to an object path and later GETs the same path
- **THEN** the same bytes are streamed back with the stored content type

### Requirement: Object metadata and checksums as headers
`HEAD /v1/buckets/{bucket}/objects/{object}` SHALL return the object's metadata as response headers
(content type, size, generation, and crc32c/md5 checksums) without a body; a `GET` SHALL carry the
same metadata headers alongside the streamed body. A request MAY supply an expected-checksum header
that is enforced on upload.

#### Scenario: HEAD returns metadata without the payload
- **WHEN** a client issues HEAD for a stored object
- **THEN** the response carries content type, size, generation, and checksum headers and no body

### Requirement: Object listing over REST
The service SHALL list objects via `GET /v1/buckets/{bucket}/objects` supporting a `prefix` filter and
opaque page-token pagination, returning JSON entries (served from the read model, eventually
consistent).

#### Scenario: List by prefix with pagination
- **WHEN** a client lists a bucket's objects with a prefix and a page token
- **THEN** the JSON response contains the matching entries and a next page token when more remain

### Requirement: Scoped authentication on the REST surface
When authentication is enabled, the REST object endpoints SHALL require a bearer token whose scope
satisfies the operation — write scope for mutating operations (bucket create/delete, object
put/delete) and read scope for reads (get/head/list) — returning `401` for a missing/unknown token and
`403` for an insufficient scope, consistent with the gRPC surface.

#### Scenario: A read token is refused a write over REST
- **GIVEN** authentication is enabled and a read-scoped token
- **WHEN** the client attempts a mutating REST operation
- **THEN** the request is rejected with `403`

### Requirement: Behavioral parity with the gRPC object API
Every REST object/bucket operation SHALL be a thin adapter over the same `ObjectService`, bucket
entities, and `BlobStore` as the gRPC API — no separate business logic — so generations, checksums,
event-sourced effects, and error semantics match those of the equivalent gRPC call.

#### Scenario: REST and gRPC produce the same catalog effect
- **WHEN** an object is committed via REST
- **THEN** it is observable via the gRPC API (and vice versa) with the same generation and checksums

### Requirement: In-transit protection for REST object data
Because the REST surface carries object bytes and bearer tokens, the service SHALL be able to serve it
over TLS consistent with the gRPC surface's transport-security posture; serving REST in cleartext SHALL
be a deliberate LAN-only configuration, not the implicit posture for a data-carrying endpoint.

#### Scenario: REST can be served over TLS
- **WHEN** transport security is enabled
- **THEN** the REST object endpoints are served over TLS rather than cleartext

### Requirement: OpenAPI description of the REST surface
The REST object API SHALL be described by an Apollo-local OpenAPI document kept in sync with the
implemented endpoints, so clients and tooling can discover and exercise it.

#### Scenario: The REST surface has a discoverable contract
- **WHEN** a developer needs to integrate with the REST API
- **THEN** an OpenAPI document describes the bucket/object endpoints, their bodies, and their responses

### Requirement: Cache validators on object reads

Object `GET` and `HEAD` responses SHALL carry a strong `ETag` derived from the object's stored md5,
so a client can revalidate a copy it already holds. The same object SHALL yield the same validator on
`GET` and on `HEAD`, and overwriting an object with different content SHALL change it.

#### Scenario: A successful read carries a validator

- **WHEN** an object is fetched with `GET`
- **THEN** the response is `200` and carries an `ETag` derived from the object's md5

#### Scenario: HEAD and GET agree

- **WHEN** the same object is fetched with `HEAD` and with `GET`
- **THEN** both responses carry the same `ETag`

#### Scenario: Overwriting an object changes its validator

- **GIVEN** an object that has been read once
- **WHEN** it is overwritten with different content and read again
- **THEN** the `ETag` differs from the one observed before the overwrite

#### Scenario: Edge case — an overwrite with identical bytes keeps the validator

- **GIVEN** an object that has been read once
- **WHEN** it is overwritten with byte-identical content and read again
- **THEN** the `ETag` is unchanged, because the representation a client holds is still current

### Requirement: Conditional object reads

Object `GET` and `HEAD` SHALL honour `If-None-Match`. When a supplied validator matches the object's
current validator, the service SHALL respond `304 Not Modified` with no body, and SHALL resolve the
answer from object metadata **without reading the blob store**. When it does not match, the request
SHALL be served normally.

#### Scenario: A matching validator is answered without a body

- **GIVEN** a client holding the current `ETag` for an object
- **WHEN** it sends `GET` with `If-None-Match` set to that value
- **THEN** the response is `304` with no body, and no blob-store read is performed

#### Scenario: A stale validator is served normally

- **GIVEN** a client holding an `ETag` from before the object was overwritten
- **WHEN** it sends `GET` with `If-None-Match` set to that value
- **THEN** the response is `200` with the current content and the current `ETag`

#### Scenario: A conditional read of a missing object is not found

- **WHEN** `GET` with `If-None-Match` names an object that does not exist
- **THEN** the response is `404`, not `304`

#### Scenario: Edge case — an unconditional read is unaffected

- **WHEN** an object is fetched with no `If-None-Match` header
- **THEN** the response is `200` with the full body, exactly as before this capability existed

### Requirement: Truthful cache directives on the object API

Object responses SHALL declare cache directives consistent with Apollo's mutability: a stored object
is reachable at a stable path whose content can be replaced, so responses SHALL require revalidation
before reuse and SHALL NOT be marked immutable or given a positive freshness lifetime. Responses
SHALL be marked as not storable by shared caches, since object reads are authenticated when
authentication is enabled.

Error responses SHALL be marked non-storable, so that a client which reads an object before it exists
does not retain that failure.

#### Scenario: Object reads require revalidation

- **WHEN** an object is fetched
- **THEN** the response's `Cache-Control` requires revalidation before reuse and is scoped to a
  private cache, and declares neither `immutable` nor a positive `max-age`

#### Scenario: A not-found response is not storable

- **WHEN** a read names an object that does not exist
- **THEN** the `404` response declares that it must not be stored

