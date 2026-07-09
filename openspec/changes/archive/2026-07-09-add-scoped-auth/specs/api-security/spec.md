# api-security — Spec Delta

Adds operation-scoped authorization on top of bearer-token authentication (design D57–D61).

## ADDED Requirements

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
