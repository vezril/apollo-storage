# access-tracking

## ADDED Requirements

### Requirement: Successful object reads are observed

Every successful read of an object SHALL be attributed to its `(bucket, key)`: `GET`, `HEAD`, and
`304`. Failed reads (`404`, `401`, `403`) SHALL NOT. Access SHALL be observed from **both** the
REST and gRPC adapters. The scenarios below are the authoritative cases.

#### Scenario: A successful GET is counted
- **WHEN** an object is read with HTTP `GET` returning `200`
- **THEN** its `access_count` is incremented and its `last_access_at` set to the response time
  (reflected after the next flush)

#### Scenario: A HEAD is counted
- **WHEN** an object's metadata is read with HTTP `HEAD` returning `200`
- **THEN** its `access_count` is incremented and its `last_access_at` updated

#### Scenario: A 304 is counted
- **WHEN** a conditional `GET` responds `304 Not Modified`
- **THEN** its `access_count` is incremented and its `last_access_at` updated

#### Scenario: A 404 is not counted
- **WHEN** a `GET` or `HEAD` for an object that does not exist responds `404`
- **THEN** no counter changes and no row is created

#### Scenario: gRPC reads are counted on the same object
- **WHEN** the same object is read over gRPC rather than REST
- **THEN** the same counter moves — the recorder is invoked from both adapters

### Requirement: Access is reflected in the read model

The read model SHALL carry, per live object, `last_access_at` (timestamp, nullable) and
`access_count` (non-negative integer, defaulting to zero). These fields SHALL be returned in object
reads and in the listing API alongside the existing metadata.

`last_access_at` is nullable so that "never been read" is distinguishable from "read once at upload
time"; an overwrite clears the fields because the previous blob is being replaced; a delete removes
the row. The scenarios below spell out each case.

#### Scenario: A never-read object reports null access time
- **GIVEN** an object that has been written but never read
- **THEN** its `last_access_at` is `null` and its `access_count` is `0` in the read model

#### Scenario: A reread advances both fields
- **GIVEN** an object with `access_count = 3` and `last_access_at = t0`
- **WHEN** it is read again at `t1 > t0`
- **THEN** after the next flush its `access_count` is `4` and `last_access_at` is `t1`

#### Scenario: Overwriting an object clears access history
- **GIVEN** an object at generation 1 with `access_count > 0`
- **WHEN** it is overwritten, producing generation 2
- **THEN** its `access_count` is `0` and `last_access_at` is `null`

### Requirement: Access recording does not block reads

Observing a read SHALL NOT be on the response path — a slow or failing recorder SHALL NOT slow or
fail a successful read. Hits are buffered in memory and flushed in batches; flush failures retry;
the buffer is bounded and overflow is observable via a metric. A crash loses the current window,
bounded by the flush interval; the scenarios below are the edges that must hold.

#### Scenario: A flush failure does not break reads
- **GIVEN** the recorder cannot reach the database
- **WHEN** a `GET` is served
- **THEN** the `GET` succeeds normally and the flush is retried; no response fails because of the
  recorder

#### Scenario: Buffer overflow is visible
- **WHEN** hits arrive faster than they can be flushed and the buffer reaches its cap
- **THEN** the oldest hits are dropped and a `access_recorder_dropped_hits` counter increments

### Requirement: A decaying access score is computed

The read model SHALL carry an `access_score` per object — a non-negative number that rises on a hit
and decays with time, so recent frequent access scores higher than long-ago frequent access. The
score SHALL be a pure function of `(prior_score, prior_time, now, hits_in_window)`; the formula
SHALL be defined in one place in `core/`.

This change provides the score; **classification** (Hot/Warm/Cold thresholds) is out of scope and
belongs to a later tiering change.

#### Scenario: Score rises on a hit
- **GIVEN** an object with score `s0` at time `t0`
- **WHEN** a hit is recorded at `t1 >= t0`
- **THEN** the new score `s1 >= s0 * decay(t1 - t0)` and strictly greater than it

#### Scenario: Score decays with time alone
- **GIVEN** two objects with identical score and no further reads
- **WHEN** one has aged `d` longer than the other
- **THEN** its score is strictly lower
