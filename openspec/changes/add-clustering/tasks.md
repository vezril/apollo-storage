# Tasks — add-clustering

TDD is non-negotiable: every implementation task is preceded by a failing-test task
and followed by a refactor + run-tests task. Do not start an implementation task
while its tests are green or missing.

Multi-node behavior is exercised with in-JVM clusters (multiple `ActorSystem`s /
`ActorTestKit`) and single-node-cluster sharding tests; genuine multi-host behavior
is validated by the deployment smoke.

## 1. Build & cluster configuration

- [x] 1.1 Add `pekko-cluster-typed`, `pekko-cluster-sharding-typed`, `pekko-management-cluster-bootstrap`, `pekko-management-cluster-http` deps (aligned versions)
- [ ] 1.2 Base cluster config — `provider = cluster`, Artery remoting (env-overridable bind/host/port), roles, `min-nr-of-members`
- [ ] 1.3 **Tests first**: config test — split-brain resolver (keep-majority) is the configured downing provider; sharding `number-of-shards` and `ShardedDaemonProcess` instance count resolve from config with env overrides (edge cases, design D27/D30)
- [ ] 1.4 **Green**: SBR + sharding + management + discovery config with homelab defaults

## 2. Cluster formation & runtime bootstrap

- [ ] 2.1 **Red**: single-node cluster formation test — the node starts management + bootstrap, forms a cluster of one, and reaches `Up`; health reflects cluster readiness (edge cases, design D28)
- [ ] 2.2 **Green**: start Pekko Management + Cluster Bootstrap in `Main`; gate work on cluster `Up` / `min-nr-of-members`

## 3. Cluster-sharded bucket entities

- [ ] 3.1 **Red**: sharding tests (single-node cluster) — an `EntityRef` for `bucket|<name>` routes commands to one entity; the persistence id is exactly `bucket|<name>`; a passivated entity recovers state on the next command (edge cases, design D29)
- [ ] 3.2 **Green**: `BucketSharding` — init Cluster Sharding for `BucketEntity` (`EntityTypeKey "bucket"`, passivation), replacing `BucketEntityManager`
- [ ] 3.3 **Green**: switch `ObjectService`/`ObjectApiImpl` entity resolution to `ClusterSharding.entityRefFor` (synchronous `EntityRef`)
- [ ] 3.4 **Refactor**: remove `BucketEntityManager`; run the entity/API suites

## 4. Distributed projection

- [ ] 4.1 **Red**: distribution test (single-node cluster) — the projection runs via `ShardedDaemonProcess` over N slice ranges covering all 1024 slices; events across buckets all land in the read model (no slice skipped/duplicated) (edge cases, design D30)
- [ ] 4.2 **Green**: wire the projection through `ShardedDaemonProcess(system).init` over `EventSourcedProvider.sliceRanges(N)`, one `ProjectionBehavior` per range (supersedes single-instance D25)

## 5. Graceful departure & rolling deploys

- [ ] 5.1 **Red**: departure test — on a node leaving, its shards/projection ranges relocate and no committed data is lost (in-JVM two-system cluster) (edge cases, design D31)
- [ ] 5.2 **Green**: wire shard handoff + entity passivation + projection rebalance into Coordinated Shutdown

## 6. Deployment, docs & verification

- [ ] 6.1 Update the image/compose for multi-node — management + remoting ports, discovery (headless service / compose contact points), a 3-replica example; DDL unchanged
- [ ] 6.2 Write README "Running a cluster": formation, SBR, ports, scaling, and the single-Postgres caveat
- [ ] 6.3 **Verify**: bring up a multi-node cluster (compose scale / k8s), confirm formation, a put on one node is listable via another, and a rolling restart keeps the service healthy
- [ ] 6.4 **Refactor + verify**: run full unit + integration suites, `scalafmtCheckAll`
