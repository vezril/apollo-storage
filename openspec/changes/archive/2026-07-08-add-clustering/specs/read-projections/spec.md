# read-projections — Spec Delta

Records how clustering changes projection **distribution** (design D30). The read
model, offsets, ordering, and eventual-consistency requirements are unchanged.

## ADDED Requirements

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
