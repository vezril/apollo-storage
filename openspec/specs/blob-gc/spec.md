# blob-gc

Reconciling the blob store against live object state to reclaim orphaned payloads, safely
(design D50–D56). Orphans arise from overwrites, crashes between persisting a payload and its
event, and failed best-effort deletes, plus `.tmp` debris. The sweep is off by default,
admin-triggered, dry-run by default, and grace-period-guarded.

## Requirements

### Requirement: Orphan reconciliation against the live set

The service SHALL identify orphaned blobs by comparing the payloads stored under a bucket
against that bucket's **live** `BlobRef`s obtained from the authoritative bucket entity (a
strongly-consistent read). A stored blob SHALL be treated as orphaned only if no live object
references it. The read model SHALL NOT be used as the live-set source.

#### Scenario: A live blob is never an orphan
- **GIVEN** a stored blob referenced by a live object in its bucket entity
- **WHEN** reconciliation runs
- **THEN** that blob is not reported or reclaimed as an orphan

#### Scenario: An unreferenced blob is an orphan candidate
- **GIVEN** a stored blob that no live object in its bucket references (e.g. a superseded
  generation or crash debris)
- **WHEN** reconciliation runs
- **THEN** it is identified as an orphan candidate (subject to the grace period)

### Requirement: Grace period protects recent and in-flight blobs

Reconciliation SHALL only treat a blob as reclaimable if its last-modified age is at least a
configurable grace period (default 24 hours). A blob younger than the grace period SHALL be
spared even if unreferenced, so a payload persisted just before its commit event — or any
freshly written file — is never reclaimed out from under an in-flight operation.

#### Scenario: A recently written unreferenced blob is spared
- **GIVEN** an unreferenced blob whose file age is less than the grace period
- **WHEN** reconciliation runs
- **THEN** it is not reclaimed (it may still be committing)

#### Scenario: Edge case — stale temp-write debris is reclaimable
- **GIVEN** a `.tmp` write artifact older than the grace period
- **WHEN** reconciliation runs
- **THEN** it is eligible for reclamation as debris

### Requirement: Dry-run by default with an explicit delete confirmation

A sweep SHALL default to **dry-run** — producing a report of scanned, live, orphaned, and
byte counts — and SHALL NOT delete anything unless the caller explicitly confirms deletion.
When deletion is confirmed, reclamation SHALL be best-effort: a failed unlink is logged and
counted, never fatal, and the sweep reports what was actually reclaimed.

#### Scenario: Dry-run reports but does not delete
- **WHEN** a sweep runs without an explicit delete confirmation
- **THEN** it returns counts of orphans and bytes and deletes nothing

#### Scenario: Confirmed sweep reclaims best-effort
- **GIVEN** a sweep run with an explicit delete confirmation
- **WHEN** it reclaims orphans and one unlink fails
- **THEN** the failure is logged and counted and the sweep still reports the reclaimed total

### Requirement: Admin-triggered sweep, guarded

The sweep SHALL be invocable only by an explicit administrative trigger (never automatically),
exposed as an endpoint that is disabled by default and, when API authentication is enabled,
requires a valid credential. The trigger SHALL return the sweep report.

#### Scenario: Sweep runs on demand and returns a report
- **GIVEN** the admin trigger enabled
- **WHEN** an operator invokes it
- **THEN** the sweep runs and returns the report (scanned / orphaned / reclaimed / bytes)

#### Scenario: Edge case — the trigger requires auth when auth is on
- **GIVEN** API authentication enabled
- **WHEN** the admin trigger is invoked without a valid credential
- **THEN** it is rejected, unauthenticated
