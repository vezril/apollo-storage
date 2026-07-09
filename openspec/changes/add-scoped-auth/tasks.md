# Tasks — add-scoped-auth

TDD is non-negotiable: every implementation task is preceded by a failing-test task and
followed by a refactor + run-tests task. Auth stays default-off; back-compat (legacy tokens =
`write`) is explicitly tested so existing deployments are unaffected.

## 1. Scope + principal model & config

- [ ] 1.1 **Red**: `AppConfig` tests — `AUTH_PRINCIPALS="a:read,b:write"` parses to scoped
  principals; legacy `AUTH_TOKENS` entries parse as `write`; an unknown scope or a `:` in a token
  fails fast (design D57/D58/D61)
- [ ] 1.2 **Green**: `Scope` (`Read`/`Write`, `write ⊇ read`) + `Principal(token, scope)`;
  `AuthConfig` becomes `Seq[Principal]`; `AppConfig.auth` parses both keys + validates

## 2. Authorize through the seam

- [ ] 2.1 **Red**: `TokenAuthenticator` tests — `authorize(metadata, Read)` accepts a read or write
  token; `authorize(metadata, Write)` accepts write, rejects read with `PERMISSION_DENIED`;
  missing/unknown token → `UNAUTHENTICATED`; disabled → no-op; comparison constant-time (D59)
- [ ] 2.2 **Green**: replace `check` with `authorize(metadata, required: Scope)` returning/using the
  matched `Principal`; keep the HTTP `authorizeBearer` variant returning the principal

## 3. Per-RPC enforcement

- [ ] 3.1 **Red**: over-the-wire scope tests (extend `TlsAuthSpec`-style) — a read token can
  `GetObject`/`ListObjects` but a `PutObject`/`DeleteObject`/`CreateBucket` with a read token is
  `PERMISSION_DENIED`; a write token does everything (design D60)
- [ ] 3.2 **Green**: tag each RPC with its required scope — `guarded(metadata, Scope.Write|Read)`;
  `getObject` inline `authorize(metadata, Scope.Read)`

## 4. Admin endpoint requires write

- [ ] 4.1 **Red**: `AdminRoutesSpec` — with auth enabled, `POST /admin/blob-gc` needs a
  write-scoped token; a read token → `403`/`PERMISSION_DENIED`; auth-disabled unchanged (D60)
- [ ] 4.2 **Green**: gate the admin route on `write` scope via the HTTP authorizer

## 5. Wiring & back-compat

- [ ] 5.1 **Green**: wire the principal-based authenticator in `Main`; update the existing gRPC test
  suites for the new `authorize` signature (no behaviour change when auth is off)
- [ ] 5.2 **Verify**: a legacy `AUTH_TOKENS`-only config still has full access (write) — regression
  guard so existing deployments are unaffected

## 6. Docs & verification

- [ ] 6.1 README "Securing the API": document `AUTH_PRINCIPALS` (`token:scope`), the read/write
  scopes, the no-`:`-in-tokens constraint, and that the admin sweep needs write; add
  `AUTH_PRINCIPALS` to the Configuration table
- [ ] 6.2 **Verify**: a TLS+auth container with a read token and a write token — read token reads
  but is `PERMISSION_DENIED` on a write and on `/admin/blob-gc`; write token does both
- [ ] 6.3 **Refactor + verify**: full unit + integration suites + `scalafmtCheckAll`
