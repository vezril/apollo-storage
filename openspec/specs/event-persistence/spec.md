# event-persistence

## Requirements

### Requirement: BucketEntity as an EventSourcedBehavior

`BucketEntity` SHALL wrap the pure domain transitions in a Pekko `EventSourcedBehavior` with structured persistence ID `bucket|<name>` (see design D2), persisting domain events via Pekko Persistence and replying to commands with typed success/error responses that mirror the domain's `Either`.

#### Scenario: Command produces persisted event and reply
- **Given** a fresh `BucketEntity` for `bucket|media`
- **When** `CreateBucket` is sent (via `EventSourcedBehaviorTestKit`)
- **Then** exactly one `BucketCreated` event is persisted and the reply is a success

#### Scenario: Edge case — rejected command persists nothing
- **Given** an entity whose bucket already exists
- **When** a second `CreateBucket` is sent
- **Then** the reply is `BucketAlreadyExists` and the journal receives zero new events

#### Scenario: Edge case — persistence ID format is stable
- **Given** the entity for bucket `media`
- **When** its persistence ID is inspected
- **Then** it equals `bucket|media` exactly (tested as a frozen contract — changing it breaks recovery)

### Requirement: Configurable PostgreSQL journal (r2dbc)

The journal SHALL be `pekko-persistence-r2dbc` targeting PostgreSQL, with connection parameters (host, port, database, user, password) supplied via HOCON config overridable by environment variables; no credentials in source or image.

#### Scenario: Events round-trip through real Postgres
- **Given** a Postgres testcontainer and config injected from it
- **When** `CreateBucket` then `CommitObject` are processed
- **Then** both events are readable from the journal tables with correct persistence ID and sequence numbers 1 and 2

#### Scenario: Edge case — environment overrides take precedence
- **Given** HOCON defaults and `POSTGRES_HOST`/`POSTGRES_PORT` env vars pointing at the testcontainer
- **When** the persistence layer initializes
- **Then** it connects to the env-specified instance, not the HOCON default

#### Scenario: Edge case — database unavailable at startup
- **Given** config pointing at an unreachable Postgres
- **When** the service starts
- **Then** `/health` reports `DOWN` (or the service exits non-zero after bounded retries), a clear error is logged, and no data is silently dropped

### Requirement: Crash recovery reconstructs state

After an actor system restart, a `BucketEntity` SHALL recover its full state — bucket existence, object map, and generation counters — solely by replaying its journal.

#### Scenario: Generation counters survive restart
- **Given** events `[BucketCreated, ObjectCommitted("a", gen 1), ObjectCommitted("a", gen 2)]` persisted, then the entity restarted
- **When** `CommitObject("a", …)` is processed post-recovery
- **Then** the new event carries generation 3

#### Scenario: Edge case — recovery of a deleted bucket
- **Given** a journal ending in `BucketDeleted`, then restart
- **When** any object command is sent
- **Then** the reply is `BucketNotFound` (deletion state recovered, not resurrected)

#### Scenario: Edge case — every event serializes round-trip
- **Given** a generator/sample covering every event constructor (see design D4: Jackson CBOR)
- **When** each event is serialized and deserialized through the configured serializer
- **Then** the result is equal to the original (mandatory regression suite for schema evolution)

### Requirement: Cluster-sharded single-writer bucket entities

When running as a cluster, bucket entities SHALL be hosted by Cluster Sharding so
that at most one active entity exists per bucket across the whole cluster — the
single writer to the journal for persistence id `bucket|<name>`. Sharding SHALL
preserve that persistence id exactly (the frozen contract, design D2), and idle
entities SHALL passivate and recover on demand without loss of state.

#### Scenario: One active entity per bucket cluster-wide
- **GIVEN** a multi-node cluster
- **WHEN** commands for bucket `media` arrive at different nodes
- **THEN** they are all routed to the single active `media` entity (one writer),
  never to two concurrent entities on different nodes

#### Scenario: Persistence id is unchanged under sharding
- **GIVEN** the sharded entity for bucket `media`
- **WHEN** its persistence id is inspected
- **THEN** it is exactly `bucket|media`, so existing journals recover unchanged

#### Scenario: Edge case — passivation and recovery
- **GIVEN** an idle entity that has passivated
- **WHEN** a new command for its bucket arrives
- **THEN** the entity is re-created and recovers its full state from the journal
  before handling the command

### Requirement: Query a bucket's live blob references

The bucket entity SHALL answer a read-only query returning the set of `BlobRef`s of all objects
currently live in the bucket, computed from its state without emitting an event. A tombstoned
or empty bucket SHALL return an empty set. This gives reconciliation a strongly-consistent live
set per bucket.

#### Scenario: Active bucket returns its live refs
- **GIVEN** an active bucket with several committed objects
- **WHEN** its live-blob-refs query is asked
- **THEN** it returns exactly the `BlobRef`s of the current live generations, and persists nothing

#### Scenario: Edge case — a deleted or empty bucket returns none
- **GIVEN** a tombstoned or never-populated bucket
- **WHEN** its live-blob-refs query is asked
- **THEN** it returns an empty set
