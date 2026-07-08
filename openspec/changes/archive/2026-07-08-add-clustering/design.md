# Design — add-clustering

## Context

Single-node ApolloStorage is complete but is a scaling and availability ceiling:
one projection instance (D25) and node-local entity hosting (`BucketEntityManager`).
This change makes the service a Pekko cluster so the projection distributes across
nodes — which requires the write side to be cluster-safe (a bucket must be a
cluster-wide single writer). The journal, blob store, domain, and API surface are
unchanged; this is a runtime/topology change. Decision numbering continues the
project log (previous change ended at D26).

## Goals / Non-Goals

**Goals:**
- Run as a Pekko cluster with automatic formation and deterministic partition
  handling (split-brain resolver).
- Bucket entities as cluster-sharded singletons (one writer per `bucket|<name>`).
- The read-model projection distributed across the cluster via
  `ShardedDaemonProcess`, rebalanced on membership change.
- Graceful rolling deploys (passivation + shard handoff).

**Non-Goals:**
- PostgreSQL high availability / read replicas (the shared journal stays single —
  its HA is a separate concern), multi-region, autoscaling policy, and zero-downtime
  schema migrations.

## Decisions

### D27 — Pekko Cluster with a mandatory Split Brain Resolver

Switch `pekko.actor.provider = cluster` (Artery remoting) and enable Pekko's
built-in **Split Brain Resolver** with the **keep-majority** strategy.
- **Why**: a cluster without downing will not recover from partitions; keep-majority
  is the safe default for an odd-sized homelab cluster (≥3 nodes) sharing one
  database. Gate startup with `min-nr-of-members`.
- **Alternatives**: static-quorum (needs a fixed count — brittle for elastic
  membership); keep-oldest (risk of a minority surviving); no SBR (rejected —
  unsafe).

### D28 — Automatic formation via Pekko Management + Cluster Bootstrap

Every node starts Pekko Management (HTTP) and Cluster Bootstrap, forming the cluster
by **discovery** (DNS/config) rather than hard-coded seed nodes. The management port
also serves Kubernetes-style readiness/liveness.
- **Why**: elastic membership without editing seed lists; standard for
  k8s/compose homelab. A configured contact-point fallback exists for a fixed
  compose topology.
- **Alternative**: static `seed-nodes` (rejected as the default — fragile across
  restarts/scale; kept as an escape hatch).

### D29 — Cluster Sharding for bucket entities

Replace `BucketEntityManager` with **Cluster Sharding**: `EntityTypeKey[
BucketEntity.Command]("bucket")`, entity id = bucket name, resolved via
`ClusterSharding(system).entityRefFor(TypeKey, name)`. Because Pekko forms the
persistence id as `EntityTypeKey.name + "|" + entityId`, the id stays exactly
`bucket|<name>` — **the frozen contract (D2) is preserved for free**. Entities
**passivate** when idle and recover on demand.
- **Why**: guarantees a single active entity per bucket across the cluster (no two
  writers to one journal), which node-local hosting cannot; it is the standard home
  for a persistent aggregate. `ObjectService`/API switch from an async manager
  lookup to a synchronous `entityRefFor`.
- **Alternative**: Cluster Singleton manager (rejected — one node hosts all entities,
  no horizontal scale); keeping the local manager (rejected — unsafe multi-node).

### D30 — ShardedDaemonProcess-distributed projection

Distribute the projection with `ShardedDaemonProcess(system).init`: split the 1024
slices into **N** ranges (`EventSourcedProvider.sliceRanges`), one
`ProjectionBehavior` per range, balanced across the cluster and rebalanced on
membership change. This **supersedes D25's single instance** (D25/D2 anticipated
this). Each range is a distinct `ProjectionId` — never two instances on the same
range.
- **Why**: parallelizes read-model building and removes the single-instance ceiling;
  `ShardedDaemonProcess` provides the cluster coordination that a bare
  `ProjectionBehavior` lacked.
- **Constraint**: N is fixed per deployment (changing it requires a coordinated
  restart); default N tuned to node count.

### D31 — Graceful rolling deploys

On shutdown, Coordinated Shutdown drives shard handoff and entity passivation before
the node leaves, so in-flight commands complete or relocate rather than fail. The
projection instances rebalance off the leaving node.
- **Why**: zero-drop rolling upgrades on the homelab.

### D32 — Deployment topology

Run **N replicas** (design target ≥3 for keep-majority quorum) behind a headless
service for DNS discovery; expose the management port; the Postgres journal + read
model remain a single shared instance. Remoting/artery and management ports are
env-configurable.
- **Why**: matches a small homelab k8s or multi-container compose; a single replica
  still runs (cluster of one) for dev.

### D33 — Roles (single role for v1)

All nodes run the same role (host entities and projection workers). Splitting into
`write`/`read` roles (dedicated projection nodes) is deferred.
- **Why**: uniform nodes are simplest at homelab scale; role separation is a later
  tuning knob.

## Risks / Trade-offs

- **SBR misconfiguration → data divergence** → Mitigation: keep-majority + documented
  ≥3-node quorum; validate SBR config in tests; the shared single Postgres also bounds
  divergence (one journal).
- **Discovery/bootstrap complexity** → Mitigation: Cluster Bootstrap via discovery
  with a documented compose/k8s setup and a static-contact-point fallback.
- **Single Postgres is a shared SPOF/bottleneck** → Mitigation: acknowledged;
  Postgres HA is an explicit future change; the cluster scales compute, not the DB.
- **Rolling-upgrade shard reallocation churn** → Mitigation: passivation + handoff
  (D31); tune shard count and rebalance thresholds.

## Migration Plan

Operationally significant but code-additive. `provider` becomes `cluster`; a single
replica still forms a cluster of one (dev/back-compat). Multi-replica needs the new
discovery + management + SBR config (shipped with sane defaults). The journal/read
model are unchanged — no data migration. **Rollback**: revert to `provider = local`
and the node-local manager; persisted data is untouched.

## Open Questions

- `number-of-shards` for bucket sharding (typically ~10× max nodes) and the
  `ShardedDaemonProcess` instance count N.
- Discovery method for the target homelab (Kubernetes API vs DNS vs static compose).
- Entity passivation timeout.
- Whether to introduce `write`/`read` roles before it is actually needed (D33).
