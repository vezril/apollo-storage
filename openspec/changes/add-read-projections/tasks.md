# Tasks — add-read-projections

TDD is non-negotiable: every implementation task is preceded by a failing-test
task and followed by a refactor + run-tests task. Do not start an implementation
task while its tests are green or missing.

The read side lives in the `server` module; the pure domain, the journal, and the
blob store are unchanged.

## 1. Build & read-model schema

- [ ] 1.1 Add `pekko-projection-r2dbc` + `pekko-projection-eventsourced` deps (align versions with pekko/r2dbc)
- [ ] 1.2 Add read-model DDL — `bucket_index`, `object_index`, and the projection offset store table — to `ddl/create_tables_postgres.sql` and the test DDL
- [ ] 1.3 Verify the projection offset-store schema matches the plugin's expectation (compile + a testcontainers migration smoke)

## 2. Read-model repository

- [ ] 2.1 **Red**: repository tests (testcontainers) — upsert bucket/object, delete object, delete bucket cascades objects; list buckets and list-objects-by-prefix are ordered with keyset pagination (edge cases: empty prefix, page boundary, deleted rows absent)
- [ ] 2.2 **Green**: `ReadModelRepository` (r2dbc) — upsert/delete + paginated prefix queries (design D22/D23)
- [ ] 2.3 **Refactor**: extract row mapping / pagination helper; run the repository suite

## 3. Projection handler & wiring

- [ ] 3.1 **Red**: projection integration (testcontainers) — folding `[BucketCreated, ObjectCommitted, ObjectCommitted(v2), ObjectDeleted, BucketDeleted]` yields the expected read-model rows at each step; recommit updates one row in place (edge cases, design D22)
- [ ] 3.2 **Green**: `R2dbcHandler` folding events into the read model in the projection's transaction (`EventSourcedProvider.eventsBySlices`, entity type `bucket`, design D21/D26)
- [ ] 3.3 **Red**: restart/offset test — a restarted projection resumes from its offset without duplicating rows; a fresh offset rebuilds from history (edge cases, design D21)
- [ ] 3.4 **Green**: exactly-once `R2dbcProjection` wiring; single `ProjectionBehavior` over all slices, spawned under the guardian (design D25)

## 4. Listing RPCs

- [ ] 4.1 Extend `object_api.proto`: `ListBuckets` and `ListObjects` (bucket, prefix, page_size, page_token) + response messages with next `page_token`
- [ ] 4.2 **Red**: in-process gRPC tests — `ListBuckets` ordered + paginated; `ListObjects` by prefix ordered + paginated; deleted objects absent; missing bucket ⇒ `NOT_FOUND` (edge cases, design D23)
- [ ] 4.3 **Green**: listing handlers on the gRPC service reading from `ReadModelRepository`
- [ ] 4.4 **Refactor**: dedupe pagination mapping; run the API suite

## 5. Consistency, runtime, deployment & docs

- [ ] 5.1 **Red**: eventual-consistency test — an object becomes visible to `ListObjects` within the bounded projection interval after commit, while `HeadObject` returns it immediately (edge cases, design D24)
- [ ] 5.2 **Green**: start the projection in `Main` after readiness; projection config (slice range, restart backoff) with homelab defaults
- [ ] 5.3 Update `docker-compose.yml` (read-model tables via the mounted DDL) and README: listing RPCs, `grpcurl` examples, and the eventual-consistency contract
- [ ] 5.4 **Refactor + verify**: run full unit + integration suites, `scalafmtCheckAll`, and confirm a deployment lists objects after a put
