ApolloStorage is a self-hosted object storage service inspired by Google Cloud Storage, built in Scala on Apache Pekko and exposed primarily through a gRPC API. It provides bucket-and-object semantics — buckets as flat namespaces containing objects addressed by name — with the core operations of a GCS-style API: bucket lifecycle (create, list, delete), object CRUD (upload, download, list with prefix/delimiter filtering, delete), object metadata (content type, custom key-value metadata, checksums, generation numbers), and streaming upload/download built on gRPC's streaming primitives. The API takes GCS as a design reference for resource naming, pagination, and error semantics, but deliberately diverges where a homelab-scale system benefits from simplicity — wire compatibility with Google's clients is a non-goal.
Architecturally, ApolloStorage is an event-sourced CQRS system. All state changes — bucket creation, object commits, metadata updates, deletions — are captured as domain events persisted through Pekko Persistence to a PostgreSQL journal via the pekko-persistence-r2dbc plugin, chosen for its active development and first-class integration with Pekko Projections (journal, snapshots, read-model offsets all in one Postgres instance). The write side models each bucket as a persistent typed entity (EventSourcedBehavior): the entity serializes all writes to its bucket, owns generation counters, and enforces invariants, making it the single source of truth for metadata. Entity IDs are structured (bucket|<name>) from day one so that a future shard-split — decomposing a hot bucket into finer-grained entities such as per-prefix or per-object — remains an identity-scheme evolution rather than a redesign; this is a tracked architectural decision, with the v1 trade-off (bucket-level write serialization, acceptable at homelab throughput) documented explicitly. The read side is built from Pekko Projections consuming the event journal into query-optimized PostgreSQL tables that serve list operations, prefix queries, and pagination — keeping the hot listing path off the entities entirely. Object payloads stay out of the journal: bytes stream through Pekko Streams from the gRPC client directly to the storage backend, and only on successful, checksum-verified persistence does the entity commit an ObjectCreated event referencing the stored blob. The storage backend is pluggable behind a trait, with the first-class implementation targeting the NAS mounted as a local filesystem (NFS): objects land as files in a bucket-scoped, collision-safe directory layout, written via temp-file-and-atomic-rename so a crash mid-upload never leaves a torn object. Integrity is enforced with CRC32C/MD5 checksums computed on ingest and verifiable on read, mirroring GCS.
Although the initial deployment is single-node, the architecture is explicitly designed to scale out. Because entities are event-sourced and addressed by identity, moving to a multi-node Pekko Cluster with Cluster Sharding is an operational change, not a rewrite: entity ownership migrates to shards, the Postgres journal and projections remain the shared backbone, and the blob store trait can later be implemented against replicated or distributed backends. The bucket-as-entity granularity is the known scaling ceiling and is carried in the spec as an open evolution point — if a bucket's write throughput ever becomes a bottleneck, the structured entity-ID scheme enables splitting without breaking the event history or the API. Event sourcing also buys homelab-grade operability — full audit history of every mutation, rebuildable read models, and natural support for future features like object versioning (generations are already events), lifecycle rules, resumable uploads, and signed URLs.
ApolloStorage is a production system for a homelab, not an experiment: it runs continuously, survives restarts and NAS outages without corruption (backpressure and precise gRPC error codes instead of partial writes), and exposes structured logs and Prometheus metrics from day one. Security starts with TLS on the gRPC endpoint and static API-key or mTLS authentication, with object-name sanitization to prevent path traversal into the NAS, and leaves room for per-bucket ACLs as the system grows. APIs are versioned so the roadmap — versioning, lifecycle policies, an S3/GCS-compatible HTTP gateway, multi-node clustering — can land without breaking existing clients.

Tech stack:
- Backend: Scala + Pekko + Pekko Persistence

TDD is REQUIRED (non-negotiable):
- Follow Red–Green–Refactor.
- For every behavior in the specs, write tests FIRST (failing), then implement.
- The task list must explicitly sequence: tests → implementation → refactor.
- Always RUN tests after each implementation to ensure they pass.

Include a comprehensive README.md file in the root of the project that explains how to run the application and how to run the tests.

Extra
- Use the repo located on the local filesystem `/Users/cference/Code/claude-toolkit` for relevant skills and agents.

Features (minimal, more features to be added later, this is just to get started). Features will be described as Acceptance Criteria, use these for your TDD tests, additionally, think of at least two edge cases for the tests:
1. Basic Github Project Scaffolding with CICD on Github using Github Actions
    - Version Control should follow semantic versioning schema
    - `main` branch has the latest major release (this builds and ships the main package via CICD)
    - `development` branch has the latest dev changes (experimental builds)
    - Feel free to propose a different strategy (and update this spec as needed)
2. Basic Pekko (No persistence) that can be built into a docker container exposing an health endpoint
3. Basic Commands, Events, and Data Models Necessary
4. Basic Persistence with configurable database (use postgresql for now)
5. Docker Container and publish artifact to docker hub
6. Basic README.md with description of the project, CICD banners, AI Usage Disclaimer using an SDLC Team, deployment example, and configuration example. Also add MIT license.


More features to come.

Constraints / non-goals:
- No UI
- Provide Given/When/Then acceptance criteria with at least 2 edge cases per feature.
- Functional Programming highly desired over imperative style
- Scala Best practices encouraged
- Clean Code is a must
