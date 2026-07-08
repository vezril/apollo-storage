## Why

The gRPC object API is served over cleartext HTTP/2 (h2c) with no authentication —
design decision D17 explicitly deferred TLS and auth to a trusted-LAN assumption.
That is the largest remaining production gap: anyone who can reach the port can read,
write, or delete every object. This change secures the client-facing API with
transport encryption and per-request authentication so ApolloStorage can be exposed
beyond a fully trusted network.

## What Changes

- Serve the gRPC API over **TLS** (HTTP/2 over TLS) using a server certificate +
  key loaded from a PKCS#12 keystore, configurable and mountable as a secret.
- Require **authentication** on every object/bucket RPC: a **bearer token** in gRPC
  request metadata, validated against a configured set of API tokens (constant-time
  comparison; no tokens in source or image). Missing/invalid credentials fail with
  `UNAUTHENTICATED`.
- **Authorization (v1, coarse)**: any valid token authorizes all operations. Per-
  bucket / per-role ACLs are tracked for a future change.
- Keep the **HTTP `/health`** endpoint plaintext (for container health checks) and
  the **gRPC health service unauthenticated** (standard for liveness probes).
- Make TLS and auth **toggleable by config** so local/dev and the test suite can run
  plaintext + unauthenticated, while production enables both.
- **BREAKING (operational)**: with TLS/auth enabled, clients must present a token and
  trust the server certificate; the documented `grpcurl` examples gain `-cacert` and
  an `authorization` header.

## Capabilities

### New Capabilities
- `api-security`: TLS transport for the gRPC surface and bearer-token authentication
  with constant-time validation and an `UNAUTHENTICATED` contract, plus the coarse
  authorization model and the unauthenticated-health carve-out.

### Modified Capabilities
- `object-api`: **ADD** a requirement that bucket/object RPCs require a valid
  credential when authentication is enabled (the health service stays open). No
  existing RPC behavior changes.

## Impact

- **Build**: no major new dependency — Pekko HTTP provides TLS (`ConnectionContext`),
  and the JDK loads the PKCS#12 keystore and does constant-time comparison. gRPC
  power APIs are enabled to expose request metadata to the handlers.
- **Affected code (`server`)**: TLS `ConnectionContext` in `GrpcServer.bind` from a
  configured keystore; a token authenticator + enforcement on the object API (via
  the gRPC power API metadata or an HTTP-level auth wrapper) returning
  `UNAUTHENTICATED`; TLS + token config in `AppConfig`/`application.conf`.
- **Configuration**: `apollostorage.tls.{enabled,keystore-path,keystore-password}`
  and `apollostorage.auth.{enabled,tokens}` — all env-overridable; tokens/keystore
  supplied as secrets, never committed.
- **Deployment**: mount the keystore and provide tokens via secrets; expose the TLS
  gRPC port; README gains a "Securing the API" section (generating a keystore,
  issuing tokens, `grpcurl -cacert … -H 'authorization: Bearer …'`).
- **Tests**: TLS handshake round-trip (client trusts the test cert); authenticated
  RPC succeeds, missing/invalid token ⇒ `UNAUTHENTICATED`; health reachable without
  a token; plaintext/no-auth mode still works for existing tests.

Out of scope (future changes): mutual TLS (client certificates), per-bucket/per-role
authorization, token issuance/rotation tooling, OIDC/JWT, and **inter-node Artery
remoting TLS** (the cluster transport stays on the trusted LAN for now).
