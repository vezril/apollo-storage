## Why

Authentication today is all-or-nothing: `AuthConfig` holds a flat `Seq[String]` of tokens and
`TokenAuthenticator.check` only asserts "this bearer token is one of the configured ones." Every
valid token can therefore do **everything** — create/delete any bucket, read/write any object, and
trigger the destructive `POST /admin/blob-gc` sweep. There is no way to issue, say, a **read-only
token** for a backup job or a monitoring script. The v0.6.0 auth work deliberately left a seam (a
validated principal at the `check` choke point) for exactly this; this change fills it in with the
simplest useful model.

## What Changes

- **Tokens carry an operation scope** — `read` or `write` — via a static scope map (no JWTs, no
  crypto). `write` ⊇ `read` (a write token can also read; a read token can only read).
- **`TokenAuthenticator` returns a principal** — `check` becomes `authorize(metadata, required)`:
  it validates the bearer token (→ `UNAUTHENTICATED` if missing/invalid, unchanged) **and** checks
  the principal's scope against the operation's required scope (→ **`PERMISSION_DENIED`** if
  insufficient). Comparison stays constant-time.
- **Each RPC declares its required scope** — reads (`GetObject`, `HeadObject`, `ListBuckets`,
  `ListObjects`) need `read`; writes (`CreateBucket`, `DeleteBucket`, `PutObject`, `DeleteObject`)
  need `write`. The `POST /admin/blob-gc` sweep needs `write`.
- **Config stays env-friendly** — `AUTH_PRINCIPALS="readtok:read,writetok:write"` (comma-separated
  `token:scope`). Back-compat: legacy `AUTH_TOKENS` entries are treated as `write` (full access),
  so existing deployments are unchanged. Auth remains default-off; health stays unauthenticated.

## Capabilities

### New Capabilities
<!-- none — this extends the existing api-security model -->

### Modified Capabilities
- `api-security`: **adds** operation-scoped authorization (read/write) on top of bearer-token
  authentication — tokens carry a scope, and an insufficient scope is `PERMISSION_DENIED`.
- `object-api`: the object RPCs now require the **appropriate scope** for their operation (read vs
  write), not merely any valid credential.
- `blob-gc`: the admin sweep requires a **write**-scoped credential.

## Impact

- **Code**: `AppConfig`/`AuthConfig` (a `Principal(token, scope)` model + `AUTH_PRINCIPALS` parsing,
  legacy `AUTH_TOKENS` → write); `TokenAuthenticator` (`authorize(metadata, required)` returning the
  principal, `PERMISSION_DENIED` on scope failure, HTTP variant for the admin route); per-RPC scope
  tags in `ObjectApiImpl`; the admin route requires write.
- **Deployment**: `AUTH_PRINCIPALS` documented alongside `AUTH_TOKENS`; the `token:scope` delimiter
  means tokens must not contain `:` (documented). No wire/API-surface change.
- **Out of scope**: per-bucket / resource scoping (this is operation-only), a distinct `admin` tier
  beyond `write`, JWTs / signed claims, and token issuance/rotation tooling.
