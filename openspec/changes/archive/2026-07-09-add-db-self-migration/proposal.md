## Why

Apollo does not create its own database schema — the journal + read-model DDL
(`create_tables_postgres.sql`) must be **pre-applied**. docker-compose mounts it into
Postgres' `initdb`, and the Codex k8s chart would otherwise have to bake a copy into a
ConfigMap. That forces the DDL to be duplicated per deployment and **drift** between repos
(it is already duplicated internally — repo-root + test resources). On k8s the cleaner fix,
requested by the Codex deployment work, is for **Apollo to own and apply its schema at boot**,
so a fresh Postgres — in Compose, k8s, or anywhere — just works with no external init step.

## What Changes

- **Apollo self-migrates on startup** — before the readiness gate, it applies its bundled DDL
  against `POSTGRES_*`. The DDL is idempotent (`CREATE … IF NOT EXISTS`), so it is a no-op after
  the first boot and safe to re-run. A migration failure aborts startup (fail-fast), never
  serving against a half-provisioned schema.
- **Toggleable, default-on** — `apollostorage.db.auto-migrate` (env `DB_AUTO_MIGRATE`, default
  `true`); set `false` to keep externally-managed schema.
- **Single canonical DDL** — the schema moves to a **main resource**
  (`server/src/main/resources/ddl/create_tables_postgres.sql`) so it is bundled in the image and
  is the one source of truth; the duplicate test-resource copy is removed (main resources are on
  the test classpath).
- **Drop the init dependency** — docker-compose no longer mounts the DDL into `initdb` (Apollo
  provisions it); the README "Database schema" section is updated. This mirrors Codex dropping
  its `initdb` ConfigMap.

## Capabilities

### New Capabilities
- `schema-migration`: Apollo applies its own database schema idempotently at startup, before
  serving, so no external schema-provisioning step is required.

### Modified Capabilities
<!-- none — startup gains a step, but the event-persistence / read-projections behaviour is unchanged -->

## Impact

- **Code**: a `PersistenceMigration` runner (reuses the r2dbc connection pattern of
  `PersistenceReadiness`) that loads the DDL from the classpath and executes its statements
  idempotently; `AppConfig.autoMigrate`; wiring in `Main` between the readiness probe and the
  projection start; the DDL relocated to main resources.
- **Deployment**: docker-compose drops the initdb mount; the Codex k8s chart drops its initdb
  ConfigMap (its `pg-service` needs no Apollo DDL). The app's DB user needs `CREATE TABLE`
  rights (true for a per-service Postgres it owns — documented).
- **Multi-node note**: the k8s default is a cluster-of-one, so a single migrator runs; the
  idempotent `IF NOT EXISTS` DDL is also safe under the rare concurrent-boot race (documented).
- **Out of scope**: a versioned migration framework (Flyway/Liquibase) — the schema is a single
  idempotent DDL; destructive migrations / column changes are not modelled.
