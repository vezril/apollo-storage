# api-security — Spec Delta

Transport security and authentication for the client-facing gRPC surface (design
D34–D39). Both are toggleable; when enabled they protect every object/bucket RPC.

## ADDED Requirements

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
