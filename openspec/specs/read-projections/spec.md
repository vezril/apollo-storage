# read-projections

The event-sourced read side (design D21): a Pekko Projection folds the bucket
journal into PostgreSQL query tables in the same database, with durable offsets so
it resumes exactly where it left off.

## Requirements

### Requirement: Projection maintains a queryable read model

A projection SHALL consume the bucket event journal and maintain a read model — a
bucket index and an object index — so that every committed event is reflected as a
row change: `BucketCreated` adds a bucket, `ObjectCommitted` upserts the object's
current metadata (generation, size, content type, checksums), `ObjectDeleted`
removes the object, and `BucketDeleted` removes the bucket and all its objects.

#### Scenario: Committed object appears in the object index
- **GIVEN** a bucket exists
- **WHEN** an object is committed
- **THEN** the object index eventually contains a row for it with the committed
  generation, size, content type, and checksums

#### Scenario: Recommitting updates the row in place
- **GIVEN** an object at generation 1 in the index
- **WHEN** a new version is committed
- **THEN** the index row for that key reflects generation 2 (one row per key, not two)

#### Scenario: Edge case — deletions remove rows
- **WHEN** an object is deleted
- **THEN** its row is removed from the object index
- **AND WHEN** its bucket is deleted, the bucket row and all of its object rows are removed

### Requirement: Durable offsets and restart resumption

The projection SHALL persist its stream offset durably and apply read-model changes
through an idempotent handler (idempotent upserts and deletes), so after a restart
it resumes from the last saved offset and the read model matches the journal exactly
— a reprocessed event re-applies with no visible effect (no duplicated or lost
rows). A projection starting from an empty offset SHALL rebuild the model from the
full journal history.

#### Scenario: Resume after restart without duplication
- **GIVEN** the projection has processed a set of events
- **WHEN** the projection is restarted
- **THEN** it resumes from the last committed offset and the read model matches the
  journal exactly (no duplicated or skipped rows)

#### Scenario: Edge case — rebuild from history
- **GIVEN** a journal with existing events and no stored offset
- **WHEN** the projection first runs
- **THEN** it folds the entire history into the read model

### Requirement: Eventual-consistency contract

Reads served from the read model SHALL be eventually consistent — reflecting the
journal up to the projection's current offset — while single-object reads
(`HeadObject`/`GetObject`) remain strongly consistent from the entity. The lag is
bounded by the projection's refresh interval.

#### Scenario: Listing catches up to a write
- **WHEN** an object is committed
- **THEN** within the projection's bounded refresh interval it becomes visible to
  listing, even though it may not be visible in the instant the commit returns

#### Scenario: Single-object read is not subject to projection lag
- **GIVEN** an object was just committed
- **WHEN** `HeadObject` is called for it
- **THEN** it returns the object immediately, independent of projection progress

### Requirement: Cluster-distributed projection

When running as a cluster, the projection SHALL run as N slice-range instances
distributed across the cluster via `ShardedDaemonProcess`, each covering a disjoint
range of the 1024 slices under its own projection id, so that no slice is processed
by two instances at once. On membership change the instances SHALL rebalance across
the available nodes, and together they SHALL still fold the whole journal into the
read model.

#### Scenario: Slices are partitioned across instances
- **GIVEN** a cluster running N projection instances
- **WHEN** events across many buckets are committed
- **THEN** each slice is handled by exactly one instance, and the read model reflects
  all committed events (no slice processed twice, none skipped)

#### Scenario: Edge case — rebalance on membership change
- **GIVEN** projection instances distributed across nodes
- **WHEN** a node leaves or joins
- **THEN** the instances rebalance across the remaining nodes and continue from their
  saved offsets without reprocessing already-applied events into duplicate rows
