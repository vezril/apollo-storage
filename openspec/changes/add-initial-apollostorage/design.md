# Design — add-initial-apollostorage

## Context

Greenfield build of a homelab-production object store. Decisions below were settled during spec discussion and are binding for this change; each records the trade-off so future changes can revisit deliberately.

## Decisions

### D1 — Journal: PostgreSQL via `pekko-persistence-r2dbc`

Chosen over the legacy JDBC plugin: actively developed, first-class Pekko Projections integration, and journal + snapshots + read-model offsets live in **one** Postgres instance — ideal operationally for a homelab.
**Trade-off**: R2DBC driver stack is newer; mitigated by integration tests against real Postgres (testcontainers).

### D2 — Entity granularity: bucket-as-entity (v1), shard-split tracked

Each bucket is a single persistent typed entity (`EventSourcedBehavior`). It serializes all writes to the bucket, owns the generation counters, and enforces invariants.
**Known scaling ceiling (tracked)**: bucket-level write serialization. Acceptable at homelab throughput. Entity IDs are structured — `bucket|<name>` — from day one, so decomposing a hot bucket into finer-grained entities (`bucket|<name>|prefix|<p>` or per-object) later is an identity-scheme evolution that preserves event history and the API. Any future change touching entity identity MUST reference this decision.

### D3 — Payloads out of the journal

The journal stores metadata events only. Object bytes stream (Pekko Streams) to the blob store; `ObjectCreated` is committed only after checksum-verified persistence. Keeps the journal small, replay fast, and the blob store swappable. (Blob store itself is a future change; the event/command model in this change is designed for it.)

### D4 — Event serialization: Jackson CBOR (v1), protobuf tracked

Events use Pekko's Jackson CBOR serialization with explicit schema-evolution discipline (additive fields, defaults, no renames). **Tracked**: migrating event serialization to protobuf/ScalaPB is coherent later since gRPC already brings protobuf into the build; deferred to avoid coupling the pure domain to generated code in v1.

### D5 — Module layout

Two sbt modules:
- `core` — pure domain: value types, commands, events, state transitions. Zero Pekko dependencies. Unit-tested exhaustively.
- `server` — runtime: Pekko entity behaviors, persistence wiring, health endpoint, main. Integration-tested.

Keeps FP purity enforceable by the dependency graph, not by discipline alone.

### D6 — Versioning & branching strategy (refined from initial prompt, as invited)

The initial prompt said "`main` has the latest **major** release". Refined to the following, which preserves the intent (main = what runs in prod-homelab) while matching semver mechanics:

- **`main`** — latest **stable release** (any semver level). Protected; PR-only; merges come from `development` via release PRs.
- **`development`** — integration branch; all feature branches PR into it.
- **Releases** are cut by pushing a tag `vX.Y.Z` on `main`. The release workflow builds and publishes the Docker image tagged `X.Y.Z` and `latest`.
- **Experimental builds**: every push to `development` publishes an image tagged `dev` and `dev-<short-sha>`. These tags are explicitly unstable.
- Version derivation in the build uses **sbt-dynver** (version comes from git tags/describe — no version number committed to source).
- Semver semantics: breaking API/event-schema change → MAJOR; backward-compatible feature → MINOR; fix → PATCH.

**Trade-off**: two long-lived branches add merge ceremony vs trunk-based; accepted because the homelab "prod" pulls `latest` and needs `main` to always be deployable.

### D7 — Health endpoint: HTTP in this change, gRPC health protocol later

This change's runtime has no gRPC surface yet, so health is a minimal Pekko HTTP endpoint (`GET /health`) suitable for Docker/Compose healthchecks. When the gRPC API lands (future change), the standard `grpc.health.v1` service is added alongside; the HTTP endpoint remains for container orchestration convenience.

### D8 — Configuration

Typesafe Config (HOCON) with environment-variable overrides for all deployment-varying values (Postgres host/port/db/user/password, HTTP bind host/port). No secrets in the repo or image. Integration tests inject config from testcontainers.

## Risks

- **R2DBC plugin maturity** → covered by D1 mitigation.
- **Event schema evolution mistakes** → serialization round-trip tests are mandatory for every event (see `event-persistence` spec).
- **CI secret misconfiguration publishing broken images** → release workflow fails fast before push if credentials are absent; semver image tags are treated as immutable (see `release-publishing` spec).

## Open Questions (deferred, not blocking)

- Snapshot cadence for `BucketEntity` (tune when object counts grow).
- Whether `development` images should also run the full integration suite against a matrix of Postgres versions.
