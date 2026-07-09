## Context

The schema in `create_tables_postgres.sql` covers the Pekko r2dbc journal tables
(`event_journal`, `snapshot`, `durable_state`), the projection offset stores, and the
read-model tables (`bucket_index`, `object_index`) — 114 lines, all `CREATE TABLE/INDEX IF
NOT EXISTS`, no functions or `DO` blocks. Apollo has never created it: `PersistenceReadiness`
only probes reachability (`SELECT 1`), and the DDL is applied externally (Compose `initdb`;
tests apply it via JDBC). The Codex k8s work needs Apollo to provision its own schema so the
`pg-service` chart doesn't carry a duplicated ConfigMap.

## Goals / Non-Goals

**Goals:**
- A fresh Postgres (Compose, k8s, bare) works with **no external schema step**.
- One canonical DDL, bundled in the image, no cross-repo or in-repo duplication.
- Idempotent, fail-fast, and toggleable for externally-managed schemas.

**Non-Goals:**
- A versioned migration tool (Flyway/Liquibase) — the schema is a single idempotent DDL.
- Destructive migrations, column/type changes, or rollbacks.
- Changing the journal/read-model schema itself.

## Decisions

### D62 — Self-migrate by executing the bundled idempotent DDL at boot
`PersistenceMigration.run(cfg)` opens a short-lived r2dbc connection (the same
`ConnectionFactories` path `PersistenceReadiness` uses), reads
`ddl/create_tables_postgres.sql` from the classpath, splits it into statements, and executes
them in order. The DDL's `CREATE … IF NOT EXISTS` makes it a no-op after first boot and safe to
re-run (including over a Compose DB previously seeded by `initdb`). Rationale: the schema is
small, static, and idempotent — a full migration framework is unjustified weight; executing the
vendored DDL keeps one source of truth. Alternative (Flyway) rejected as over-engineered for a
single idempotent script.

### D63 — Statement splitting on `;`, ordered, sequential
Split the DDL on `;`, drop blank/comment-only chunks, and execute each statement **sequentially
in file order** (tables precede their indexes). Safe because this DDL contains no `;` inside
string literals or comments. Sequential execution keeps table-before-index ordering and makes a
failure point precise. (A note in the DDL warns against introducing `;`-bearing literals.)

### D64 — Runs after the reachability probe, before serving; fail-fast
Startup order becomes: `PersistenceReadiness.check` (DB reachable, with retries) → **migrate**
(if enabled) → `startProjection` → readiness `true`. Migration runs once the DB is confirmed
reachable and **before** any entity write or projection query, so the schema always exists
before the service serves. A migration failure logs and **exits non-zero** — identical to the
existing readiness-failure handling — never serving against a partial schema.

### D65 — Default-on, toggleable
`apollostorage.db.auto-migrate` (env `DB_AUTO_MIGRATE`, default `true`). Default-on delivers the
"just works" goal; `false` leaves schema to an external owner (a DBA-managed migration, or a
locked-down DB user without DDL rights). Documented that the DB user needs `CREATE TABLE` rights
when auto-migrate is on — true for the per-service Postgres Apollo owns.

### D66 — One canonical DDL as a main resource; drop the init dependency
Relocate the DDL to `server/src/main/resources/ddl/create_tables_postgres.sql` so it is bundled
in the jar/image (reachable by the running app) and remains on the test classpath (tests keep
loading it via `fromResource`). Remove the duplicate `server/src/test/resources/ddl/` copy.
docker-compose drops the `initdb` mount (self-migration replaces it), and the README schema
section is rewritten. This is the change that lets Codex drop its `initdb` ConfigMap.

## Risks / Trade-offs

- **Concurrent-boot race on multi-node** → the k8s default is a cluster-of-one (single migrator);
  `CREATE … IF NOT EXISTS` is also safe if two nodes race (worst case a transient duplicate-object
  error, retried). Documented; a leader-only migration is a future refinement if multi-node lands.
- **DB user lacks DDL rights** → migration fails fast at boot with a clear error; the operator
  either grants `CREATE` or sets `DB_AUTO_MIGRATE=false`. Documented.
- **Someone adds a `;`-bearing literal to the DDL** → the naive splitter would mis-split; guarded
  by a comment in the DDL and by the migration test (which executes the real DDL end-to-end).
- **Double-apply over an initdb-seeded DB** → harmless (idempotent), so Compose users who keep an
  old mount are unaffected.

## Migration Plan

Additive: the new startup step is a no-op on an already-provisioned DB. Deploy the image; a fresh
Postgres self-provisions. Compose: drop the initdb mount (or leave it — idempotent). k8s: Codex
drops the ConfigMap and the chart just points Apollo at its Postgres. Rollback: set
`DB_AUTO_MIGRATE=false` and provision the schema externally (the DDL is still shipped in the image
and repo).

## Open Questions

- Whether to add leader-only migration when a genuine multi-node deploy arrives (deferred; the
  idempotent DDL is safe enough for now).
