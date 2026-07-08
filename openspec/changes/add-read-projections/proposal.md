## Why

The bucket aggregate can be commanded and a single object can be fetched by key,
but there is no way to **list** buckets or the objects in a bucket — the object
API deliberately deferred listing because it needs a read side. This change adds
the CQRS read side: a Pekko Projection that folds the event journal into query
tables in the same PostgreSQL, and listing RPCs served from those tables. This is
what makes the store browsable.

## What Changes

- Add a **Pekko Projection** (r2dbc, `eventsBySlices`) that consumes the bucket
  event journal and maintains a **read model** in PostgreSQL: a bucket index and
  an object index (bucket, object key, generation, size, checksums, content type,
  updated-at), updated on `BucketCreated`/`BucketDeleted`/`ObjectCommitted`/
  `ObjectDeleted`.
- Store projection **offsets** in Postgres (the r2dbc offset store) so the read
  model resumes exactly where it left off after a restart, with backtracking
  de-duplication and per-persistence-id ordering.
- Add listing RPCs to the gRPC API: **`ListBuckets`** and **`ListObjects`**
  (bucket + optional key prefix + pagination), served from the read model.
- Reads are **eventually consistent** (bounded by projection lag); single-object
  `HeadObject`/`GetObject` remain strongly consistent from the entity. This is
  documented as an explicit contract.

Out of scope (future changes): full-text/metadata search, delimiter-based
"directory" listing beyond simple prefix, object version history queries, and
multi-node projection sharding (single projection instance for the homelab).

## Capabilities

### New Capabilities
- `read-projections`: the event-sourced read side — a Postgres-backed projection
  of the journal into bucket/object query tables, with durable offsets, ordering,
  and eventual-consistency guarantees.

### Modified Capabilities
- `object-api`: **ADD** `ListBuckets` and `ListObjects` RPCs (prefix + pagination)
  served from the read model. No existing object-api requirement changes.

## Impact

- **Build**: add `pekko-projection-r2dbc` (and `pekko-projection-eventsourced`);
  no new datastore — the read model, offsets, and journal share one Postgres.
- **Affected code (`server`)**: a projection handler folding events into the read
  model; read-model repository (r2dbc queries); projection wiring in `Main`
  (single `ProjectionBehavior` under the guardian); new listing handlers on the
  gRPC service reading from the repository; proto additions for `ListBuckets`/
  `ListObjects` and pagination.
- **Schema**: new `bucket_index`, `object_index`, and projection-offset tables
  (added to the deployment DDL and the testcontainers setup).
- **Configuration**: projection settings (slice range, restart backoff) with sane
  homelab defaults.
- **Tests**: projection integration tests (testcontainers) asserting events fold
  into the read model with correct rows; listing RPC tests (prefix, pagination,
  post-delete removal); eventual-consistency read-your-write-after-lag behavior.
