# Design — add-tls-auth

## Context

The gRPC API is served h2c with no authentication (design D17, deliberately deferred
to a trusted LAN). This change adds transport encryption and per-request auth to the
client-facing surface. The cluster's inter-node Artery transport is explicitly out of
scope here. Decision numbering continues the project log (previous change ended at D33).

## Goals / Non-Goals

**Goals:**
- TLS (HTTP/2 over TLS) for the gRPC surface, from a configurable keystore.
- Per-request bearer-token authentication with a constant-time check and an
  `UNAUTHENTICATED` contract.
- A coarse authorization model (valid token ⇒ all operations) and an
  unauthenticated carve-out for health.
- Toggleable by config so dev/tests run plaintext + open.

**Non-Goals:**
- Mutual TLS (client certs), per-bucket/per-role authorization, token
  issuance/rotation tooling, OIDC/JWT, and inter-node Artery TLS.

## Decisions

### D34 — In-process TLS from a PKCS#12 keystore

Serve gRPC over TLS by building a `ConnectionContext.httpsServer` from an
`SSLContext` loaded from a **PKCS#12 keystore** (cert + private key) at a configured
path/password. `GrpcServer.bind` uses the HTTPS context when TLS is enabled.
- **Why**: self-contained (no TLS sidecar/ingress required for a homelab), and
  PKCS#12 + `SSLContext` are JDK-native (no extra dependency, unlike PEM parsing).
- **Alternatives**: TLS termination at an ingress/sidecar (rejected as the baseline —
  adds moving parts for a single-box homelab; still possible in front); raw PEM files
  (rejected — needs a parser dependency).

### D35 — Bearer-token auth via the gRPC power API

Enable Pekko gRPC **power APIs** so each RPC receives request `Metadata`. A shared
`TokenAuthenticator.authenticate(metadata)` reads the `authorization: Bearer <token>`
entry and validates it; handlers call it first and fail with `UNAUTHENTICATED` when
absent/invalid.
- **Why**: idiomatic gRPC (credentials in metadata), lets handlers emit a proper
  gRPC status, and keeps auth logic in one place. Tokens travel in metadata, protected
  by TLS (D34).
- **Alternatives**: an HTTP-level wrapper inspecting the `authorization` header
  (rejected — must hand-craft gRPC status trailers on rejection); mTLS client certs
  (deferred — cert lifecycle overhead).

### D36 — Coarse authorization (v1)

Any valid token authorizes every operation. Per-bucket and per-role ACLs are a
tracked future change.
- **Why**: a single homelab operator needs a gate, not a policy engine, yet; the auth
  seam (a validated principal) is in place for finer rules later.

### D37 — Unauthenticated health carve-out

The `grpc.health.v1.Health` service and the HTTP `/health` endpoint SHALL remain
reachable **without** a token, so container/orchestrator liveness and readiness probes
work.
- **Why**: probes must not carry app credentials; health leaks no object data.

### D38 — Config toggles, default off, fail-fast + loud

`apollostorage.tls.enabled` and `apollostorage.auth.enabled` default to **false** so
the image still boots standalone (dev/homelab) and the existing test suite runs
plaintext. When enabled, a missing keystore or empty token set is a **fast startup
failure**. If auth is enabled while TLS is disabled, the service logs a prominent
warning (tokens would travel in cleartext). Disabled auth/TLS also logs a warning at
startup.
- **Why**: backward-compatible default keeps the released image bootable and the CI
  green, while production turns both on via config/secrets. Loud logs prevent
  silently-insecure deployments.
- **Trade-off**: not secure-by-default; mitigated by prominent warnings and docs.

### D39 — Constant-time token validation, secrets only

Configured tokens are secrets (env / mounted file), never in source or image.
Validation compares the presented token against each configured token with a
constant-time comparison (`MessageDigest.isEqual` on UTF-8 bytes) to avoid timing
side-channels; a non-match set yields `UNAUTHENTICATED`.
- **Why**: prevents byte-by-byte timing discovery of a valid token.

## Risks / Trade-offs

- **Insecure if an operator forgets to enable auth/TLS** → Mitigation: prominent
  startup warnings (D38) and a documented "Securing the API" runbook.
- **Auth-on / TLS-off sends tokens in cleartext** → Mitigation: explicit startup
  warning for that combination (D38).
- **Keystore/token misconfiguration** → Mitigation: fail fast at startup with a clear
  message naming the missing keystore/tokens.
- **Power-API regeneration touches every handler** → Mitigation: mechanical change;
  covered by the existing gRPC test suite plus new auth tests.

## Migration Plan

Additive and backward-compatible: TLS and auth default off, so the current image,
compose stack, and tests are unchanged. Production enables them via
config/secrets (keystore mount + token list) and switches clients to TLS + a bearer
token. **Rollback**: set `tls.enabled=false` / `auth.enabled=false`.

## Open Questions

- Whether to ship a helper script to generate a self-signed keystore for homelab use.
- Token format/length guidance and whether to store token **hashes** in config rather
  than raw secrets.
- When to make auth/TLS default-on (e.g., a future major version).
