# event-persistence — Spec Delta

Records how clustering changes entity **hosting** (design D29). The entity behavior,
journal, serialization, and recovery requirements are unchanged.

## ADDED Requirements

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
