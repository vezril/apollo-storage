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
APOLLOSTORAGE_IMAGE=apollostorage:$(sbt -Dsbt.log.noformat=true -batch -error 'print server/version' | tail -n1 | perl -pe 's/\e\[[0-9;]*[a-zA-Z]//g' | tr -d '[:space:]') docker compose up
```

### Build the Docker image

```bash
sbt server/Docker/publishLocal
```

Produces a non-root image with an `EXPOSE`d HTTP port and a container
`HEALTHCHECK` against `/health`.

## gRPC object API

The service exposes a gRPC API (`apollostorage.grpc.ObjectApi`) on `GRPC_PORT`
(default `8443`, cleartext HTTP/2) for the bucket and object lifecycle, plus the
standard `grpc.health.v1.Health` service. HTTP `/health` remains for container
orchestration.

| RPC | Shape | Purpose |
| --- | --- | --- |
| `CreateBucket` / `DeleteBucket` | unary | bucket lifecycle |
| `PutObject` | client-streaming (header then chunks) | upload a payload |
| `GetObject` | server-streaming (header then chunks) | download a payload |
| `HeadObject` | unary | object metadata only |
| `DeleteObject` | unary | delete an object |

Domain outcomes map to gRPC status codes (`NOT_FOUND`, `ALREADY_EXISTS`,
`INVALID_ARGUMENT`, `FAILED_PRECONDITION`). Example with
[`grpcurl`](https://github.com/fullstorydev/grpcurl) (the service is not on a TLS
port, so use `-plaintext`):

```bash
# create a bucket
grpcurl -plaintext -d '{"bucket":"media"}' \
  localhost:8443 apollostorage.grpc.ObjectApi/CreateBucket

# health check
grpcurl -plaintext -d '{}' localhost:8443 grpc.health.v1.Health/Check
```

> **Note:** the API is served over cleartext HTTP/2 (h2c) for the trusted homelab
> LAN; TLS and authentication are a tracked future change (design D17).

## Mounting NFS object storage

> **Status:** The blob store that persists object payloads outside the journal
> (design D3) is implemented and covered by tests: committed payloads are streamed
> to disk under the blob root with checksum verification and crash-safe atomic
> writes, and the service verifies the root is writable at startup. The
> client-facing object API (gRPC upload/download, D7) that *drives* commits is a
> later change. Orphaned blobs — from superseded versions or a failed delete — are
> reclaimed by a future reconciliation sweep. Mount your storage at the blob root
> (`BLOB_STORE_PATH`, default `/var/lib/apollostorage/objects`).

The examples below mount an NFS export from your NAS into the service container
at `/var/lib/apollostorage/objects`. Replace `192.168.1.10` with your NAS
address and `:/volume1/apollostorage/objects` with your export path.

### Option A — Compose-managed NFS volume (recommended)

Let Docker mount the export directly as a named volume — nothing needs to be
mounted on the host first. Add to `docker-compose.yml`:

```yaml
services:
  apollostorage:
    # ...existing config...
    volumes:
      - objects:/var/lib/apollostorage/objects

volumes:
  # ...existing pgdata volume...
  objects:
    driver: local
    driver_opts:
      type: nfs
      o: "addr=192.168.1.10,nfsvers=4.1,rw,hard,timeo=600,retrans=2"
      device: ":/volume1/apollostorage/objects"
```

`hard` mounts favor data integrity — I/O is retried rather than failing silently
during a transient NAS outage. Use `soft,timeo=30` instead only if you prefer
fail-fast behavior over blocking.

### Option B — Bind-mount a path already mounted on the host

If the share is mounted on the Docker host (e.g. via `/etc/fstab`):

```
# /etc/fstab on the Docker host
192.168.1.10:/volume1/apollostorage/objects  /mnt/apollo-objects  nfs4  rw,hard,timeo=600  0  0
```

```yaml
services:
  apollostorage:
    volumes:
      - /mnt/apollo-objects:/var/lib/apollostorage/objects
```

### Plain `docker run`

Create the NFS-backed volume once, then attach it:

```bash
docker volume create --driver local \
  --opt type=nfs \
  --opt o=addr=192.168.1.10,nfsvers=4.1,rw,hard,timeo=600 \
  --opt device=:/volume1/apollostorage/objects \
  apollo-objects

docker run -d --name apollostorage \
  -p 8080:8080 \
  -e POSTGRES_HOST=postgres -e POSTGRES_PASSWORD=... \
  -v apollo-objects:/var/lib/apollostorage/objects \
  calvinference/apollostorage:latest
```

### Permissions

The container runs as a **non-root** user (`uid 1001`, `apollo`), so the export
must let that user write. Either:

- make the export directory writable by `uid 1001` on the NAS
  (e.g. `chown -R 1001:1001 /volume1/apollostorage/objects`, or grant group
  write and give the owning group `gid 1001`); **or**
- map NFS writes to a writable owner via the export options
  (`anonuid=1001,anongid=1001` with `all_squash`).

Confirm the mount is writable from inside the running container:

```bash
docker compose exec apollostorage \
  sh -c 'id && cd /var/lib/apollostorage/objects && touch .wtest && echo "writable" && rm .wtest'
```

## Configuration

All deployment-varying values come from HOCON (`application.conf`) and are
overridable by environment variables — no secrets live in the repo or image.

| HOCON key                                              | Env var                   | Default        |
| ------------------------------------------------------ | ------------------------- | -------------- |
| `apollostorage.http.host`                              | `HTTP_HOST`               | `0.0.0.0`      |
| `apollostorage.http.port`                              | `HTTP_PORT`               | `8080`         |
| `apollostorage.blob.root`                              | `BLOB_STORE_PATH`         | `/var/lib/apollostorage/objects` |
| `apollostorage.grpc.port`                              | `GRPC_PORT`               | `8443`         |
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
