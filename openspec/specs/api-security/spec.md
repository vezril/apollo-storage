# api-security

Transport security and authentication for the client-facing gRPC surface (design
D34–D39). Both TLS and token authentication are toggleable and default off; when
enabled they protect every object/bucket RPC while leaving health endpoints open.

## Requirements

### Requirement: TLS transport for the gRPC API

When TLS is enabled, the gRPC surface SHALL be served over TLS (HTTP/2 over TLS) using
a server certificate and private key loaded from a configured PKCS#12 keystore; no
key material SHALL live in source or the image. When TLS is disabled, the surface is
served cleartext (h2c) for local/dev use.

#### Scenario: TLS-enabled server completes a handshake
- **GIVEN** TLS enabled with a keystore whose certificate the client trusts
- **WHEN** the client connects to the gRPC port
- **THEN** the TLS handshake succeeds and RPCs proceed over the encrypted channel

#### Scenario: Edge case — missing keystore fails fast
- **GIVEN** TLS enabled but the configured keystore is absent or the password is wrong
- **WHEN** the service starts
- **THEN** it fails fast at startup with a clear error naming the keystore, rather than
  serving cleartext

### Requirement: Bearer-token authentication

When authentication is enabled, every bucket/object RPC SHALL require a valid API token
supplied as `authorization: Bearer <token>` in request metadata, validated against the
configured token set with a constant-time comparison. A missing or invalid token SHALL
fail the RPC with `UNAUTHENTICATED` and perform no side effect.

#### Scenario: Valid token is accepted
- **GIVEN** authentication enabled and a configured token
- **WHEN** a `CreateBucket` RPC carries `authorization: Bearer <that token>`
- **THEN** the RPC is processed normally

#### Scenario: Edge case — missing token is rejected
- **WHEN** an object RPC is sent with no `authorization` metadata
- **THEN** it fails with `UNAUTHENTICATED` and nothing is created, read, or deleted

#### Scenario: Edge case — invalid token is rejected
- **WHEN** an object RPC carries a bearer token that is not in the configured set
- **THEN** it fails with `UNAUTHENTICATED`

### Requirement: Health endpoints remain unauthenticated

The `grpc.health.v1.Health` service and the HTTP `/health` endpoint SHALL be reachable
without a token even when authentication is enabled, so liveness/readiness probes work
and no object data is exposed.

#### Scenario: Health check needs no credential
- **GIVEN** authentication enabled
- **WHEN** `grpc.health.v1.Health/Check` is called with no `authorization` metadata
- **THEN** it returns the serving status normally

### Requirement: Insecure configuration is surfaced loudly

The service SHALL log a prominent startup warning when TLS or authentication is
disabled, and when authentication is enabled while TLS is disabled (bearer tokens would
then travel in cleartext), so a silently-insecure deployment is visible.

#### Scenario: Auth-without-TLS warns
- **GIVEN** authentication enabled and TLS disabled
- **WHEN** the service starts
- **THEN** it logs a warning that bearer tokens are sent without transport encryption

### Requirement: Operation-scoped authorization

Each configured token SHALL carry an operation **scope** — `read` or `write` — and the service
SHALL authorize every authenticated request against the scope the operation requires, where a
`write` token satisfies a `read` requirement but a `read` token does not satisfy a `write`
requirement. A valid token whose scope is insufficient for the operation SHALL be rejected with
`PERMISSION_DENIED` (distinct from the `UNAUTHENTICATED` returned for a missing or unknown token).
Scopes SHALL be supplied statically (a token→scope map, no signed claims); a legacy flat token
list SHALL be treated as `write` (full access) for backward compatibility. The service SHALL fail
fast at startup on a malformed scope configuration.

#### Scenario: A read token is refused a write operation
- **GIVEN** authentication enabled and a token scoped `read`
- **WHEN** it is presented to a write operation (e.g. `PutObject`)
- **THEN** the request is rejected with `PERMISSION_DENIED` and nothing is mutated

#### Scenario: A write token may also read
- **GIVEN** a token scoped `write`
- **WHEN** it is presented to a read operation (e.g. `GetObject`)
- **THEN** the request is authorized

#### Scenario: A read token may read
- **GIVEN** a token scoped `read`
- **WHEN** it is presented to a read operation
- **THEN** the request is authorized

#### Scenario: Edge case — a legacy unscoped token has full access
- **GIVEN** a token configured through the legacy flat list
- **WHEN** it is presented to any operation
- **THEN** it is treated as `write` scope and authorized, so existing deployments are unchanged

#### Scenario: Edge case — malformed scope config fails fast
- **GIVEN** authentication enabled with a principal declaring an unknown scope
- **WHEN** the service starts
- **THEN** it aborts at startup rather than serving a mis-authorizing configuration
