# blob-storage — Spec Delta

Additions supporting orphan reconciliation (design D51/D55): enumerating stored payloads and
reclaiming a superseded payload on overwrite.

## ADDED Requirements

### Requirement: Enumerate stored payloads for reconciliation

The blob store SHALL support enumerating the payloads it holds for a bucket — each as its
`BlobRef` with a last-modified timestamp — and separately enumerating stale temp-write
artifacts, so a reconciliation pass can compare on-disk payloads against live references.
Enumeration operates **per bucket**, so the whole store is never assembled at once.

#### Scenario: Enumeration yields stored refs with ages
- **GIVEN** a bucket with several stored payloads
- **WHEN** the store is enumerated for that bucket
- **THEN** it yields each payload's `BlobRef` and last-modified time

#### Scenario: Temp-write artifacts are enumerable separately
- **GIVEN** a `.tmp` artifact left by an aborted write
- **WHEN** temp artifacts are enumerated
- **THEN** the artifact is listed with its age

### Requirement: Reclaim the superseded payload on overwrite

The object service SHALL reclaim the **superseded** payload best-effort when a commit overwrites
an existing object name (producing a new generation with a new `BlobRef`), after the new
`ObjectCommitted` is persisted. A failed reclaim SHALL be logged and leave an orphan (caught
later by reconciliation), and SHALL never fail the commit or lose the newly committed payload.

#### Scenario: Overwrite deletes the prior generation's payload
- **GIVEN** an existing object and a new commit to the same name
- **WHEN** the new generation is committed
- **THEN** the prior generation's payload is deleted best-effort, so the overwrite does not
  orphan it

#### Scenario: Edge case — a failed reclaim does not fail the commit
- **GIVEN** an overwrite whose superseded-blob delete fails
- **WHEN** the commit completes
- **THEN** the commit still succeeds and the superseded payload is logged as an orphan
