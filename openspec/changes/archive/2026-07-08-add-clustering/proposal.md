## Why

ApolloStorage runs as a single node: the projection is one instance (design D25),
and bucket entities are hosted by a node-local `BucketEntityManager`. That is a
throughput and availability ceiling — one process is the whole system. To scale
horizontally and survive a node failure, the service must run as a **Pekko
cluster**: the read-model projection distributed across nodes (the headline ask),
which in turn requires the write side to be cluster-safe.

## What Changes

- Form a **Pekko cluster** across replicas, with automatic membership and a
  **split-brain resolver** (keep-majority) so partitions resolve deterministically.
- **Cluster-shard the bucket entities** — replace the single-node
  `BucketEntityManager` with Cluster Sharding so each bucket is a **cluster-wide
  singleton** entity (never two writers to `bucket|<name>`), addressed by
  `EntityRef`. Persistence IDs and the journal are unchanged (design D2).
- **Distribute the projection** with `ShardedDaemonProcess`: split the 1024 slices
  into N ranges, one `ProjectionBehavior` per range, spread across the cluster and
  rebalanced on membership change (supersedes the single-instance D25).
- **Cluster formation** via Pekko Management + discovery (DNS/config), with a
  management HTTP port for bootstrap and health.
- Graceful **rolling deploys**: entity passivation and shard handoff on shutdown so
  a node can leave without dropping in-flight work.
- **BREAKING (operational)**: the service now expects to run as a cluster (≥1 node,
  designed for ≥3 for quorum). A single-replica deployment still works but a
  multi-replica deployment requires the new discovery + management configuration.

## Capabilities

### New Capabilities
- `clustering`: cluster formation and membership, split-brain resolution,
  cluster-sharded bucket entities, and `ShardedDaemonProcess`-distributed
  projections — the multi-node runtime foundation.

### Modified Capabilities
- `event-persistence`: entity **hosting** changes from a node-local manager to
  Cluster Sharding (each bucket a cluster singleton). The entity behavior, journal,
  serialization, and recovery are unchanged; a delta records the new
  single-writer-across-the-cluster guarantee.
- `read-projections`: the projection changes from a single instance to a
  cluster-distributed set of slice-range instances; offsets, ordering, and
  eventual consistency are unchanged (a delta records the distribution).

## Impact

- **Build**: add `pekko-cluster-typed`, `pekko-cluster-sharding-typed`,
  `pekko-management-cluster-bootstrap`, `pekko-discovery` (already present), and a
  split-brain resolver (Pekko's built-in SBR). No new datastore.
- **Affected code (`server`)**: cluster + management bootstrap in `Main`;
  `BucketSharding` (Cluster Sharding init) replacing `BucketEntityManager`;
  `ObjectService`/API resolve `EntityRef` via sharding; projection wired through
  `ShardedDaemonProcess` over slice ranges; SBR + sharding config.
- **Configuration**: `pekko.actor.provider = cluster`, remote/artery bind,
  management + discovery settings, SBR strategy, shard count, projection instance
  count — all env-overridable.
- **Deployment**: multi-replica with a headless service (DNS discovery) or
  configured contact points; management port exposed; README + compose/k8s notes
  for a 3-node cluster; single Postgres remains shared (its own HA is out of scope).
- **Tests**: multi-node cluster-sharding tests (multi-JVM or `ActorTestKit` with a
  small cluster), sharded-projection distribution + rebalance, single-writer
  guarantee under two nodes, and SBR configuration validation.

Out of scope (future changes): PostgreSQL high availability / read replicas,
multi-region, autoscaling policy, and zero-downtime schema migrations.
