# Design — add-read-projections

## Context

The write side (bucket entity + blob store + gRPC API) is complete, but there is
no queryable view: listing was deferred pending a read side. Design decision D1
chose `pekko-persistence-r2dbc` partly *because* it brings first-class Pekko
Projections with `eventsBySlices`. This change adds that read side — a projection
of the journal into query tables in the same Postgres — and the listing RPCs.
Decision numbering continues the project log (previous change ended at D20).

## Goals / Non-Goals

**Goals:**
- A durable, restart-safe projection folding bucket events into a Postgres read
  model (bucket index + object index).
- `ListBuckets` and `ListObjects` (prefix + pagination) served from that model.
- A clear consistency contract: listings are eventually consistent; single-object
  reads stay strongly consistent.

**Non-Goals:**
- Full-text/metadata search, delimiter-based directory listing, version-history
  queries, and multi-node projection sharding (single instance for the homelab).

## Decisions

### D21 — Pekko Projections (r2dbc, eventsBySlices, exactly-once)

Consume `EventSourcedProvider.eventsBySlices[Event]` from the r2dbc read journal
and run an `R2dbcProjection.exactlyOnce` projection. The handler writes read-model
rows using the projection's `R2dbcSession`, so the row change and the offset commit
happen in **one transaction**.
- **Why**: exactly-once + same-transaction offset means no duplicate or lost
  read-model updates even across restarts; `eventsBySlices` is the rescalable,
  recommended source (D1). Backtracking duplicates are de-duplicated by the
  Projection, which also enforces per-persistence-id order.
- **Alternatives**: `atLeastOnce` with idempotent upserts (viable, but exactly-once
  is simpler to reason about here); tag-based `eventsByTag` (rejected — not
  rescalable, D1 chose slices).

### D22 — Read model schema

Two tables plus the projection offset table, all in the journal's Postgres:
- `bucket_index(bucket PK, created_at)`.
- `object_index(bucket, object_key, generation, size, content_type, crc32c, md5,
  updated_at, PRIMARY KEY(bucket, object_key))`.

The handler folds events: `BucketCreated` → insert bucket; `ObjectCommitted` →
upsert object row (`ON CONFLICT` update); `ObjectDeleted` → delete object row;
`BucketDeleted` → delete the bucket row and all its objects.
- **Why**: a compound key `(bucket, object_key)` supports prefix scans and keyset
  pagination directly; upserts keep the handler idempotent as a belt to D21.

### D23 — Listing API: keyset pagination over a key prefix

`ListObjects(bucket, prefix, page_size, page_token)` returns rows ordered by
`object_key`, filtered by `object_key LIKE prefix || '%'`, starting after
`page_token` (the last key of the previous page); the response carries the next
`page_token` (empty when exhausted). `ListBuckets(page_size, page_token)` is the
same over `bucket`.
- **Why**: keyset pagination is stable under concurrent writes and avoids
  large-`OFFSET` scans.
- **Alternative**: offset/limit paging (rejected — O(n) skips, unstable pages).

### D24 — Consistency contract: eventually-consistent listings

Listings reflect the journal up to the projection's current offset and therefore
**lag writes** by up to the projection interval; an object may not appear in
`ListObjects` immediately after `PutObject` returns. `HeadObject`/`GetObject`
remain **strongly consistent** (served from the entity, not the read model). This
split is documented in the API and README.
- **Why**: standard CQRS; the entity is the source of truth, the read model is a
  derived, catch-up view.

### D25 — Single projection instance (sharding tracked)

Run one `ProjectionBehavior` covering all 1024 slices, spawned under the guardian.
- **Why**: homelab is single-node; one instance handling the full slice range is
  simplest and correct. `ShardedDaemonProcess` across a cluster is a future change,
  aligned with the entity-sharding note in **design D2**. Any future change
  distributing the projection MUST reference D2/D25.
- **Constraint**: never run two instances with the same `ProjectionId` — the
  single-instance guardian spawn enforces this on one node.

### D26 — Entity type binding

The source provider queries `entityType = "bucket"`, matching the persistence-id
scheme `bucket|<name>` (design D2). The projection deserializes the same Jackson
CBOR events (D4) via the existing serializer — no serialization change.

## Risks / Trade-offs

- **Eventual-consistency surprises** → Mitigation: documented contract (D24);
  integration tests await propagation rather than asserting immediately.
- **Read-model / offset schema drift** → Mitigation: ship the tables in the
  deployment DDL and the testcontainers setup; additive only.
- **Single projection is a throughput ceiling** → Mitigation: acceptable at homelab
  scale; slice-range sharding is a tracked future change (D25).
- **Rebuild cost on first deploy** → the projection replays from offset 0, folding
  full history into the model; bounded by journal size, one-time.

## Migration Plan

Additive: new tables (added to `ddl/create_tables_postgres.sql` and applied by the
compose Postgres init and the tests) and a new build dependency. No change to the
journal, blob store, or existing RPCs. **Rollback**: revert — the read tables are
simply left unused; the journal (source of truth) is untouched.

## Open Questions

- Default and maximum `page_size`.
- Whether to add a delimiter parameter for pseudo-directory listings before it is
  actually needed.
- Projection restart-backoff tuning for a flapping database.
