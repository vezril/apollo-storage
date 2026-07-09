## Context

`add-tls-auth` (D34–D39) added bearer-token authentication: `AuthConfig(enabled, tokens:
Seq[String])` and `TokenAuthenticator.check(metadata): Unit`, which throws `UNAUTHENTICATED` unless
the presented token is in the set. Object RPCs call it through a `guarded(metadata){…}` helper
(`getObject` inlines the same check), and the `POST /admin/blob-gc` route validates via
`authorizeBearer`. Every valid token is fully privileged. D35 explicitly noted the `check` choke
point as "a validated-principal seam ... in place for finer rules later." This change realises the
finest useful rule: a **read vs write operation scope** per token.

## Goals / Non-Goals

**Goals:**
- Issue least-privilege tokens — a **read-only** token (backup/monitoring) that cannot mutate.
- Reuse the existing seam; keep the change small and the config env-driven.
- Distinguish "no/invalid token" from "valid token, wrong scope" in the status returned.

**Non-Goals:**
- Per-bucket / resource scoping (operation-only for now; a natural later axis).
- A distinct `admin` tier beyond `write`.
- JWTs / signed claims / token issuance + rotation tooling.
- Changing the gRPC surface or the wire contract.

## Decisions

### D57 — Two operation scopes, `read` and `write`, with `write ⊇ read`
A token's scope is `Read` or `Write`; a `Write` token satisfies a `Read` requirement (it can do
everything a full token does today), a `Read` token satisfies only `Read`. Rationale: this covers
the concrete need (a read-only backup/monitor token) with the smallest possible model and no
resource bookkeeping. Alternatives: per-bucket scoping (deferred — a later axis), full RBAC
(overkill for a handful of LAN clients).

### D58 — Static token→scope map, env-encoded, with legacy back-compat
Scopes live in a static map, not a signed claim. Config: `AUTH_PRINCIPALS="tok:read,tok2:write"` —
comma-separated `token:scope` pairs, parsed like the existing `AUTH_TOKENS`. Legacy `AUTH_TOKENS`
entries are read as **`write`** scope, so existing deployments keep full access unchanged.
Rationale: no crypto/dependencies, and it matches the established secrets-via-env pattern (a mounted
HOCON principals file was the alternative — rejected as heavier than this deployment needs). The
`:` delimiter means tokens must not contain a colon — a documented, startup-validated constraint.

### D59 — `authorize(metadata, required)` returns/validates a principal
`TokenAuthenticator.check` becomes `authorize(metadata, required: Scope)`: it resolves the bearer
token to a `Principal(token, scope)` (still a constant-time match), throwing `UNAUTHENTICATED` when
the token is missing or unknown — unchanged — and **`PERMISSION_DENIED`** when the principal's scope
does not satisfy `required`. This fills the seam: authentication answers "who", authorization
answers "may they". The two distinct statuses make a misconfigured client debuggable (wrong token
vs wrong scope).

### D60 — Required scope declared per RPC at the call site
Each RPC tags its operation: `guarded(metadata, Scope.Write){…}` for writes, `Scope.Read` for
reads; `getObject` uses `authorize(metadata, Scope.Read)` inline. Classification follows the proto —
reads: `GetObject`, `HeadObject`, `ListBuckets`, `ListObjects`; writes: `CreateBucket`,
`DeleteBucket`, `PutObject`, `DeleteObject`. The admin `POST /admin/blob-gc` route requires
`Write` (it is destructive); `authorizeBearer` returns the principal so the route can enforce it.
Health stays unauthenticated (D37, unchanged).

### D61 — Fail-fast on misconfiguration
Startup aborts (extending the existing empty-tokens fail-fast) when auth is enabled with no
principals, or a principal declares an unknown scope, or a token contains the `:` delimiter — so a
broken auth config fails loudly at boot, never silently mis-authorizing at runtime.

## Risks / Trade-offs

- **Silent lockout of existing deployments** → avoided: legacy `AUTH_TOKENS` map to `write` (full
  access), so nothing changes for current tokens; scoped tokens are strictly opt-in via
  `AUTH_PRINCIPALS`.
- **A `:` in a token breaks parsing** → validated at startup (fail-fast) and documented; tokens are
  operator-generated opaque secrets, so avoiding one character is cheap.
- **Confusing a read token for a write one** → returns `PERMISSION_DENIED` with a clear message, not
  a silent no-op, so the misconfiguration is obvious.
- **Scope creep toward RBAC** → deliberately capped at two operation scopes; per-bucket and an
  `admin` tier are noted as future axes, not built.

## Migration Plan

Additive and backward-compatible. Deploy: existing `AUTH_TOKENS` continue to work as full-access
(`write`). To use least-privilege, issue read tokens via `AUTH_PRINCIPALS` (e.g. a read-only backup
token). Rollback: drop `AUTH_PRINCIPALS` (and everything reverts to the flat full-access list); no
data or wire impact.

## Open Questions

- Whether to add a distinct **`admin`** scope later (so a write token can't run GC) — deferred;
  `write` gates the admin endpoint for now.
- Whether **per-bucket** scoping is the next increment once operation scopes are in use.
