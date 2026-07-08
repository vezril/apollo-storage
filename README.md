<!-- Badges reference this repo slug; adjust OWNER if the GitHub path differs. -->

# ApolloStorage

[![CI](https://github.com/vezril/apollo-storage/actions/workflows/ci.yml/badge.svg)](https://github.com/vezril/apollo-storage/actions/workflows/ci.yml)
[![Release](https://github.com/vezril/apollo-storage/actions/workflows/release.yml/badge.svg)](https://github.com/vezril/apollo-storage/actions/workflows/release.yml)
[![Dev publish](https://github.com/vezril/apollo-storage/actions/workflows/dev.yml/badge.svg)](https://github.com/vezril/apollo-storage/actions/workflows/dev.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A GCS-inspired, event-sourced object store built for homelab-production use.
Buckets and objects are modeled as an **event-sourced CQRS** aggregate: every
state change is a persisted domain event, and current state is folded from that
event log. This first milestone is the runnable foundation — a Pekko service
with a health endpoint, the pure domain model, and an event-sourced PostgreSQL
journal — that later features (gRPC object API, blob storage, read-side
projections, streaming) build on.

## Architecture at a glance

| Module   | Responsibility                                                                 |
| -------- | ------------------------------------------------------------------------------ |
| `core`   | Pure domain — validated value types, command/event ADTs, state transitions. Zero Pekko dependencies. |
| `server` | Runtime — Pekko typed `ActorSystem`, HTTP health endpoint, `BucketEntity` event-sourced behavior, PostgreSQL journal via `pekko-persistence-r2dbc`. |

- **Journal:** PostgreSQL through `pekko-persistence-r2dbc` (metadata events only; object bytes are a future blob-store change).
- **Serialization:** Jackson CBOR with additive-only schema evolution.
- **Entity identity:** each bucket is one persistent entity with id `bucket|<name>`.
- **Versioning:** derived from git tags via sbt-dynver — no version literal in source.

## AI Usage Disclaimer

ApolloStorage is developed by an **AI software-development team**: a suite of
[Claude Code](https://claude.com/claude-code) agents acting as product owner,
developers, and reviewers, following a spec-driven (OpenSpec) TDD workflow. All
agent output is **reviewed by a human** (the repository owner) before it is
merged. AI-generated code carries the same correctness bar as any other code
here — every behavior in this milestone is covered by tests, including
testcontainers integration against real PostgreSQL.

## Quickstart

### Prerequisites

- JDK 21+
- [sbt](https://www.scala-sbt.org/)
- Docker (for the integration tests and for running the service)

### Run the tests

```bash
sbt test
```

Runs the pure `core` unit suite and the `server` suite, including
testcontainers integration tests that spin up a real PostgreSQL instance (Docker
must be running).

### Run the application (Docker Compose)

The compose file starts the service and its PostgreSQL journal, initializing the
event-journal schema on first boot:

```bash
docker compose up
# in another shell:
curl localhost:8080/health
# -> {"status":"UP","service":"apollostorage","version":"..."}
```

To run a locally built image instead of the published one:

```bash
sbt server/Docker/publishLocal
APOLLOSTORAGE_IMAGE=apollostorage:$(sbt -batch -error 'print server/version' | tail -n1) docker compose up
```

### Build the Docker image

```bash
sbt server/Docker/publishLocal
```

Produces a non-root image with an `EXPOSE`d HTTP port and a container
`HEALTHCHECK` against `/health`.

## Configuration

All deployment-varying values come from HOCON (`application.conf`) and are
overridable by environment variables — no secrets live in the repo or image.

| HOCON key                                              | Env var                   | Default        |
| ------------------------------------------------------ | ------------------------- | -------------- |
| `apollostorage.http.host`                              | `HTTP_HOST`               | `0.0.0.0`      |
| `apollostorage.http.port`                              | `HTTP_PORT`               | `8080`         |
| `pekko.persistence.r2dbc.connection-factory.host`      | `POSTGRES_HOST`           | `localhost`    |
| `pekko.persistence.r2dbc.connection-factory.port`      | `POSTGRES_PORT`           | `5432`         |
| `pekko.persistence.r2dbc.connection-factory.database`  | `POSTGRES_DB`             | `apollostorage`|
| `pekko.persistence.r2dbc.connection-factory.user`      | `POSTGRES_USER`           | `apollostorage`|
| `pekko.persistence.r2dbc.connection-factory.password`  | `POSTGRES_PASSWORD`       | `apollostorage`|
| `pekko.persistence.r2dbc.connection-factory.connect-timeout` | `POSTGRES_CONNECT_TIMEOUT` | `3 seconds` |
| `pekko.loglevel`                                       | `LOG_LEVEL`               | `INFO`         |

### Database schema

The r2dbc plugin does not create its tables automatically. Apply
[`ddl/create_tables_postgres.sql`](ddl/create_tables_postgres.sql) to your
PostgreSQL database once (the compose file mounts it into Postgres' init
directory automatically).

## Versioning & branching

- `main` — latest stable release; protected, PR-only.
- `development` — integration branch; feature branches PR into it.
- Releases are cut by pushing a `vX.Y.Z` tag on `main`, which publishes
  `X.Y.Z` and `latest` images to Docker Hub. Each push to `development`
  publishes explicitly-unstable `dev` / `dev-<short-sha>` images.

## License

Released under the [MIT License](LICENSE).
