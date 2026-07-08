# Change: add-initial-apollostorage

## Why

ApolloStorage does not exist yet. This change bootstraps the project from zero to a minimal, production-grade foundation for the homelab: a versioned GitHub repository with CI/CD, a runnable Pekko service in Docker with a health endpoint, the core domain model (commands, events, data models), event-sourced persistence to a configurable PostgreSQL journal, published Docker artifacts, and complete project documentation. Every subsequent feature (gRPC object API, blob store, projections, streaming) builds on these foundations, so they must be correct, tested, and operable from day one.

## What Changes

- **project-scaffolding** (new): GitHub repository layout, sbt build, `main`/`development` branching strategy, semantic versioning via git tags, GitHub Actions pipelines (PR verification, release, dev builds).
- **service-runtime** (new): minimal Pekko typed application (no persistence yet) with an HTTP health endpoint, graceful shutdown, and a Docker image built via sbt-native-packager.
- **domain-model** (new): pure, FP-style core — validated value types (`BucketName`, `ObjectName`, `Generation`, `Checksums`, `ObjectMetadata`), command and event ADTs, and pure state-transition logic for the bucket aggregate.
- **event-persistence** (new): `BucketEntity` as an `EventSourcedBehavior` persisting domain events through Pekko Persistence R2DBC to PostgreSQL, with externalized/env-overridable configuration and verified crash recovery.
- **release-publishing** (new): CI publishes semver-tagged images to Docker Hub from `main` releases and `dev`-tagged experimental images from `development`.
- **documentation** (new): comprehensive `README.md` (description, CI/CD badges, AI Usage Disclaimer describing the SDLC agent team, deployment example, configuration example, test instructions) and MIT `LICENSE`.

Out of scope for this change (future changes): gRPC object API surface, blob storage backend / NAS integration, read-side projections, streaming upload/download, auth/TLS.

## Impact

- Affected specs: all six capabilities above are **ADDED** (greenfield — no existing specs are modified).
- Affected code: new repository. New sbt multi-module build (`core` = pure domain, `server` = runtime/persistence), `.github/workflows/`, `Dockerfile`-equivalent via sbt-native-packager, `README.md`, `LICENSE`.
- Infrastructure: PostgreSQL required for integration tests (testcontainers) and deployment (docker compose example); Docker Hub repository + CI secrets (`DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`).
