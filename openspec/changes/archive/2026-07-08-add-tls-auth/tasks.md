# Tasks — add-tls-auth

TDD is non-negotiable: every implementation task is preceded by a failing-test task and
followed by a refactor + run-tests task. Do not start an implementation task while its
tests are green or missing.

TLS and auth are toggleable and default **off**, so the existing suite keeps running
plaintext + open; new tests exercise the enabled paths.

## 1. Configuration & TLS context

- [x] 1.1 Config in `AppConfig` + `application.conf`: `apollostorage.tls.{enabled,keystore-path,keystore-password}` and `apollostorage.auth.{enabled,tokens}`, all env-overridable (secrets never committed)
- [x] 1.2 **Red**: TLS-context tests — an `SSLContext` loads from a PKCS#12 keystore; a missing keystore or wrong password fails fast with a clear error (edge cases, design D34)
- [x] 1.3 **Green**: keystore → `SSLContext` → `ConnectionContext.httpsServer` loader

## 2. TLS-served gRPC

- [x] 2.1 **Red**: TLS handshake IT — a client trusting the test certificate connects and completes an RPC over TLS; h2c still works when TLS is disabled (edge cases, design D34)
- [x] 2.2 **Green**: `GrpcServer.bind` uses the HTTPS `ConnectionContext` when TLS is enabled

## 3. Token authentication

- [x] 3.1 Enable gRPC server power APIs; switch `ObjectApiImpl` to the power API so handlers receive request `Metadata` (design D35)
- [x] 3.2 **Red**: authenticator tests — valid token accepted; missing/invalid ⇒ `UNAUTHENTICATED`; comparison is constant-time; an empty configured token set ⇒ startup failure (edge cases, design D35/D39)
- [x] 3.3 **Green**: `TokenAuthenticator` + enforcement on every bucket/object RPC (reject with `UNAUTHENTICATED` before any side effect)
- [x] 3.4 **Red**: health carve-out test — the gRPC health service is reachable with no token while object RPCs require one (edge case, design D37)
- [x] 3.5 **Green**: leave health unauthenticated; keep object RPCs gated

## 4. Safe configuration

- [x] 4.1 **Red**: startup-warning tests — auth-enabled-with-TLS-disabled warns (cleartext tokens); disabled auth/TLS warns (edge cases, design D38)
- [x] 4.2 **Green**: wire TLS + auth from config in `Main` with fail-fast on misconfig and prominent startup warnings

## 5. Deployment, docs & verification

- [x] 5.1 Deployment: keystore mount + token secret in `docker-compose.yml` (documented, off by default); expose the TLS gRPC port
- [x] 5.2 README "Securing the API": generate a keystore, issue tokens, and call with `grpcurl -cacert … -H 'authorization: Bearer …'`
- [x] 5.3 **Verify**: run a TLS+auth-enabled container — an authenticated `grpcurl` call succeeds over TLS, an unauthenticated one is rejected, and health works without a token
- [x] 5.4 **Refactor + verify**: run full unit + integration suites and `scalafmtCheckAll`
