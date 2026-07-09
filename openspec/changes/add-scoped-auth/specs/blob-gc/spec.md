# blob-gc — Spec Delta

The admin sweep now requires a write-scoped credential (design D60).

## MODIFIED Requirements

### Requirement: Admin-triggered sweep, guarded

The sweep SHALL be invocable only by an explicit administrative trigger (never automatically),
exposed as an endpoint that is disabled by default and, when API authentication is enabled,
requires a valid **write**-scoped credential (the sweep is destructive). The trigger SHALL return
the sweep report.

#### Scenario: Sweep runs on demand and returns a report
- **GIVEN** the admin trigger enabled and a write-scoped credential (or auth disabled)
- **WHEN** an operator invokes it
- **THEN** the sweep runs and returns the report (scanned / orphaned / reclaimed / bytes)

#### Scenario: Edge case — the trigger rejects a read-scoped credential
- **GIVEN** API authentication enabled
- **WHEN** the admin trigger is invoked with a read-scoped (or missing) credential
- **THEN** it is rejected — `PERMISSION_DENIED` for a read token, unauthenticated for a missing one
