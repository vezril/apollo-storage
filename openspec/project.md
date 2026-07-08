# Project Context

## Purpose

**ApolloStorage** is a self-hosted, GCS-inspired object storage service for a production homelab. It exposes bucket-and-object semantics over a gRPC API, persists metadata as an event-sourced CQRS system, and stores object payloads on a NAS mounted as a local filesystem. It is not an experiment: it must run continuously, survive restarts and NAS outages without corruption, and grow over time (versioning, lifecycle rules, resumable uploads, signed URLs, S3/GCS HTTP gateway, multi-node clustering).

Wire compatibility with Google's clients is a **non-goal**; GCS is a design reference for resource naming, pagination, and error semantics only.

## Tech Stack

- **Language**: Scala 3.3.x (LTS)
- **Runtime**: Apache Pekko (typed actors), Pekko Streams, Pekko gRPC
- **Persistence**: Pekko Persistence with `pekko-persistence-r2dbc` → PostgreSQL (journal, snapshots, projection offsets in one instance)
- **Read side**: Pekko Projections → query-optimized PostgreSQL tables
- **Build**: sbt, sbt-native-packager (Docker), scalafmt, scalafix
- **Testing**: ScalaTest + Pekko ActorTestKit / EventSourcedBehaviorTestKit, testcontainers-scala (PostgreSQL) for integration tests
- **CI/CD**: GitHub Actions; images published to Docker Hub

## Project Conventions

### Development Process — TDD is REQUIRED (non-negotiable)

- Follow **Red–Green–Refactor** for every behavior in the specs.
- Tests are written FIRST (failing), then implementation, then refactor.
- Every task list explicitly sequences: **tests → implementation → refactor**.
- Tests are RUN after each implementation step; a task is not done until its tests pass.

### Code Style

- **Functional programming strongly preferred** over imperative style: immutable data, ADTs (sealed traits / enums), total functions, `Either`/`Option` over exceptions for domain errors, smart constructors for validated types.
- Scala best practices and Clean Code are mandatory: small pure functions, expressive names, no dead code, scalafmt-enforced formatting.
- Effects and side-channels (I/O, clock, randomness) are isolated behind traits so the domain core stays pure and unit-testable.

### Architecture Patterns

- **Event Sourcing + CQRS**: all state changes are domain events in the Postgres journal; read models are rebuilt from projections.
- **Bucket-as-entity (v1)**: each bucket is one `EventSourcedBehavior` that serializes writes, owns generation counters, and enforces invariants. Entity IDs are structured (`bucket|<name>`) so a future shard-split (per-prefix or per-object entities) is an identity-scheme evolution, not a redesign. This is a **tracked architectural decision** — see `design.md` of the active change.
- **Payloads out of the journal**: object bytes stream via Pekko Streams to the pluggable blob store (v1: NAS via NFS mount, temp-file + atomic-rename); only checksum-verified persistence commits an `ObjectCreated` event.
- **Multi-node door stays open**: entities addressed by identity → Cluster Sharding later is operational, not a rewrite.

### Testing Strategy (pyramid)

- **Unit (many, fast)**: pure domain — validation, state transitions, command handling via `EventSourcedBehaviorTestKit` with in-memory/persistence-testkit journal.
- **Integration (some)**: Postgres journal round-trips and recovery via testcontainers; HTTP/gRPC endpoints via Pekko HTTP/gRPC testkits.
- **E2E / smoke (few)**: Docker image boots, health endpoint responds; exercised in CI.

### Git & Versioning

- **Semantic versioning** driven by git tags (`vMAJOR.MINOR.PATCH`).
- `main` = latest stable release; `development` = integration branch for experimental builds. See `project-scaffolding` spec and `design.md` for the full strategy (refined from the initial prompt, as invited).

## External Tooling

- Local skills/agents repo for the implementing SDLC team: `/Users/cference/Code/claude-toolkit` (available on the developer's machine when running Claude Code locally; consult it for relevant skills and agents before starting work).

## Constraints / Non-Goals

- No UI.
- Acceptance criteria are Given/When/Then, with **at least 2 edge cases per feature**.
- v1 security scope: TLS on gRPC, static API-key or mTLS auth, object-name sanitization (no path traversal into the NAS). Per-bucket ACLs deferred. No formal threat model.
