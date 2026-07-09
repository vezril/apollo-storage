# schema-migration — Spec Delta

Apollo provisions its own database schema at startup (design D62–D66).

## ADDED Requirements

### Requirement: Idempotent schema self-migration at startup

When schema auto-migration is enabled, the service SHALL apply its bundled database DDL against
the configured PostgreSQL **before it reports ready**, creating the journal, projection-offset,
and read-model tables if they do not exist. Applying the DDL SHALL be idempotent — a no-op on an
already-provisioned database and safe to re-run — and SHALL run after the reachability probe and
before any object is served or projected.

#### Scenario: A fresh database is provisioned on boot
- **GIVEN** auto-migration enabled and an empty PostgreSQL
- **WHEN** the service starts
- **THEN** it creates the required tables and only then reports ready, so the first request works
  without any external schema step

#### Scenario: An already-provisioned database is unaffected
- **GIVEN** a database whose schema already exists
- **WHEN** the service starts with auto-migration enabled
- **THEN** re-applying the DDL changes nothing and startup proceeds normally

### Requirement: Fail-fast on a failed migration

The service SHALL abort startup with a clear error and a non-zero exit when a migration cannot
complete (e.g. the database user lacks the privileges to create tables), rather than serving
against a partially-provisioned schema.

#### Scenario: Insufficient privileges abort startup
- **GIVEN** auto-migration enabled and a DB user that cannot create tables
- **WHEN** the service starts
- **THEN** it logs the failure and exits non-zero without serving

### Requirement: Auto-migration is toggleable

Schema auto-migration SHALL be controllable by configuration (default enabled), so a deployment
that manages its schema externally can disable it and the service will start against a
pre-provisioned database without attempting DDL.

#### Scenario: Disabled auto-migration skips DDL
- **GIVEN** auto-migration disabled
- **WHEN** the service starts against a pre-provisioned database
- **THEN** it applies no DDL and serves normally
