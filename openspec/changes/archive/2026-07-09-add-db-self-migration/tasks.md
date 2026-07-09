# Tasks — add-db-self-migration

TDD is non-negotiable: every implementation task is preceded by a failing-test task and
followed by a refactor + run-tests task. Migration is idempotent and default-on; tests
exercise fresh-DB provisioning, idempotent re-run, and the disabled path.

## 1. Canonical DDL as a main resource

- [x] 1.1 Move `ddl/create_tables_postgres.sql` → `server/src/main/resources/ddl/` (bundled in the
  image, on the test classpath); remove the duplicate `server/src/test/resources/ddl/` copy; add a
  header comment warning against `;`-bearing literals (design D63/D66)
- [x] 1.2 Confirm the existing IT suites still load the DDL via `fromResource("ddl/…")` unchanged

## 2. Config

- [x] 2.1 Config in `AppConfig` + `application.conf`: `apollostorage.db.auto-migrate` (default
  `true`, env `DB_AUTO_MIGRATE`)

## 3. Migration runner

- [x] 3.1 **Red**: `PersistenceMigration` IT (testcontainers, empty DB) — running it against a
  fresh database creates the tables; a second run is a no-op; the object round-trip then works
  (design D62/D63)
- [x] 3.2 **Green**: `PersistenceMigration.run(cfg)` — r2dbc connect (reuse the `PersistenceReadiness`
  pattern), load the DDL from the classpath, split into statements, execute sequentially,
  idempotently

## 4. Startup wiring

- [x] 4.1 **Green**: in `Main`, run the migration (when enabled) after `PersistenceReadiness.check`
  and before `startProjection`; a migration failure logs + exits non-zero (design D64)
- [x] 4.2 **Verify**: unit-test-level check that disabled auto-migrate skips the runner

## 5. Drop the init dependency & docs

- [x] 5.1 docker-compose: remove the `initdb` DDL mount (Apollo self-migrates); note it in a comment
- [x] 5.2 README "Database schema": rewrite for self-migration (`DB_AUTO_MIGRATE`, the
  `CREATE TABLE` privilege note); update the DDL path link; add `DB_AUTO_MIGRATE` to the
  Configuration table; fix `scripts/verify-docs.sh` DDL path
- [x] 5.3 **Verify**: a container against a **fresh** Postgres with **no** pre-seeded schema — the
  service self-migrates, reports UP, and a `CreateBucket` + `PutObject` round-trip works
- [x] 5.4 **Refactor + verify**: full unit + integration suites + `scalafmtCheckAll`
