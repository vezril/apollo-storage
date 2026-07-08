# clustering — Spec Delta

The multi-node runtime foundation (design D27–D33): how ApolloStorage nodes form a
cluster, survive partitions, and leave gracefully. Entity sharding and projection
distribution are recorded as deltas on `event-persistence` and `read-projections`.

## ADDED Requirements

### Requirement: Automatic cluster formation

Nodes SHALL form a single Pekko cluster automatically via Pekko Management + Cluster
Bootstrap using service discovery (no hard-coded seed nodes required), and SHALL not
begin hosting entities or projection workers until the cluster has the configured
minimum number of members.

#### Scenario: Nodes discover each other and form one cluster
- **GIVEN** multiple ApolloStorage nodes started with the same discovery configuration
- **WHEN** they boot
- **THEN** they converge into a single cluster and each reports `Up` membership

#### Scenario: Edge case — startup gated on minimum members
- **GIVEN** `min-nr-of-members` is configured
- **WHEN** fewer than that many nodes are present
- **THEN** the nodes wait to host work until the minimum is reached, rather than
  splitting into separate singleton clusters

### Requirement: Split-brain resolution

The cluster SHALL run a split-brain resolver with the keep-majority strategy, so a
network partition is resolved deterministically — the majority side survives and the
minority side downs itself — preventing two independent clusters from both writing.

#### Scenario: Partition resolves to the majority
- **GIVEN** a cluster of three nodes
- **WHEN** the network partitions into a two-node side and a one-node side
- **THEN** the two-node side stays up and the one-node side downs itself

#### Scenario: Edge case — resolver is mandatory
- **GIVEN** the runtime configuration
- **WHEN** it is inspected
- **THEN** a downing provider (the split-brain resolver) is configured — the cluster
  is never left without a downing strategy

### Requirement: Graceful rolling departure

When a node shuts down (e.g. a rolling deploy), Coordinated Shutdown SHALL hand off
its shards and passivate its entities before the node leaves, and its projection
workers SHALL rebalance to the remaining nodes, so in-flight work is completed or
relocated rather than dropped.

#### Scenario: A node leaves without dropping work
- **GIVEN** a running multi-node cluster serving requests
- **WHEN** one node is told to leave (SIGTERM)
- **THEN** its shards and projection ranges relocate to other nodes and the cluster
  continues serving without losing committed data
