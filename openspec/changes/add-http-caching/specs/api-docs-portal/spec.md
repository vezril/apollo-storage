# api-docs-portal

## ADDED Requirements

### Requirement: Documentation assets are revalidated rather than re-sent

The documentation page's static assets and the served OpenAPI document SHALL carry a cache validator
and SHALL be revalidated rather than re-transferred, so that repeat visits to `/docs` do not re-send
the full asset payload.

The assets are served at paths that do not carry the packaged library's version, so they SHALL NOT be
frozen with a positive freshness lifetime — a viewer must never be pinned to a stale documentation UI
after an upgrade. A validator SHALL change when the bytes it identifies change, so that upgrading the
packaged UI or editing the OpenAPI document invalidates a client's copy.

#### Scenario: Assets carry a validator

- **WHEN** a documentation asset is requested
- **THEN** the response is `200` and carries an `ETag`

#### Scenario: A repeat visit transfers no asset payload

- **GIVEN** a client holding the current validator for an asset
- **WHEN** it re-requests that asset with `If-None-Match`
- **THEN** the response is `304` with no body

#### Scenario: The OpenAPI document is validated too

- **GIVEN** a client holding the current validator for `/docs/openapi.yaml`
- **WHEN** it re-requests the document with `If-None-Match`
- **THEN** the response is `304` with no body

#### Scenario: Different assets carry different validators

- **WHEN** two different documentation assets are requested
- **THEN** their validators differ, so a client cannot confuse one for the other

#### Scenario: A stale validator is served fresh content

- **GIVEN** a client presenting a validator that does not match the current asset
- **WHEN** it requests that asset
- **THEN** the response is `200` with the asset's current bytes and its current validator

#### Scenario: Edge case — the documentation page itself stays revalidated

- **WHEN** the documentation page at `/docs` is requested
- **THEN** its cache directives require revalidation before reuse, so a change to the page is picked
  up on the next visit rather than after an expiry window
