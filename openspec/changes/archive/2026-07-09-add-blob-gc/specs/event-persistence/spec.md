# event-persistence — Spec Delta

A read-only query on the bucket entity so reconciliation can obtain the authoritative live
`BlobRef` set (design D50).

## ADDED Requirements

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
