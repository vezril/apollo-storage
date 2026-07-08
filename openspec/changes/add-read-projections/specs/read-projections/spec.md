# read-projections — Spec Delta

The event-sourced read side (design D21): a Pekko Projection folds the bucket
journal into PostgreSQL query tables in the same database, with durable offsets so
it resumes exactly where it left off.

## ADDED Requirements

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

The projection SHALL commit its stream offset in the same transaction as the
read-model change (exactly-once), so after a restart it resumes from the last
processed event without reprocessing or losing updates, and a projection starting
from an empty offset SHALL rebuild the model from the full journal history.

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
