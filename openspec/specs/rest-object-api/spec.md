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
