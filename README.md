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
- A GitHub token with `read:packages`, exported as `LEXICON_TOKEN` (or `GITHUB_TOKEN`),
  to resolve the gRPC contract artifact — see **gRPC contract** below.

### gRPC contract (the Lexicon)

The gRPC service definition is **not** in this repo — it lives in the
[Lexicon](https://github.com/vezril/the-lexicon), the constellation's single source of
truth for wire contracts, and is consumed as a pinned artifact
(`io.codex:lexicon-grpc`) published to GitHub Packages. The server generates and
implements its stubs from that artifact rather than a local `.proto` (design
`adopt-lexicon-grpc-contracts`). Resolving it needs a `read:packages` token:

```bash
export LEXICON_TOKEN=<a GitHub PAT with read:packages>
```

In CI, supply the same value as a repository secret named `LEXICON_TOKEN`. (GitHub
Packages Maven reads require auth even for public packages, and the built-in Actions
token cannot read another repository's package.)

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
standard `grpc.health.v1.Health` service. On the HTTP port, `/health` remains for
container orchestration and `/metrics` exposes Prometheus metrics (see **Metrics &
monitoring**).

| RPC | Shape | Purpose |
| --- | --- | --- |
| `CreateBucket` / `DeleteBucket` | unary | bucket lifecycle |
| `PutObject` | client-streaming (header then chunks) | upload a payload |
| `GetObject` | server-streaming (header then chunks) | download a payload |
| `HeadObject` | unary | object metadata only |
| `DeleteObject` | unary | delete an object |
| `ListBuckets` | unary | list buckets (paginated) |
| `ListObjects` | unary | list a bucket's objects by key prefix (paginated) |

**Consistency:** `HeadObject`/`GetObject` are **strongly consistent** (served from
the entity). `ListBuckets`/`ListObjects` are **eventually consistent** — they read
a projection of the event journal and lag writes by a bounded interval, so an
object may not appear in a listing the instant `PutObject` returns.

Domain outcomes map to gRPC status codes (`NOT_FOUND`, `ALREADY_EXISTS`,
`INVALID_ARGUMENT`, `FAILED_PRECONDITION`). Example with
[`grpcurl`](https://github.com/fullstorydev/grpcurl) (the service is not on a TLS
port, so use `-plaintext`):

```bash
# create a bucket
grpcurl -plaintext -d '{"bucket":"media"}' \
  localhost:8443 apollostorage.grpc.ObjectApi/CreateBucket

# list a bucket's objects under a prefix (eventually consistent)
grpcurl -plaintext -d '{"bucket":"media","prefix":"photos/","page_size":100}' \
  localhost:8443 apollostorage.grpc.ObjectApi/ListObjects

# health check
grpcurl -plaintext -d '{}' localhost:8443 grpc.health.v1.Health/Check
```

> **Note:** by default the API is served over cleartext HTTP/2 (h2c) with no
> authentication — fine for a trusted homelab LAN. To encrypt the transport and
> require bearer tokens, see **Securing the API** below.

## Securing the API

TLS and token authentication are **opt-in and default off**. When disabled the
service logs a startup warning so an insecure deployment is never silent. Enabling
them requires no image rebuild — supply a keystore and secrets at runtime. **Never
commit keystores or tokens, or bake them into the image.**

**1. Generate a PKCS#12 keystore** (self-signed is fine on a LAN; use your CA or
[mkcert](https://github.com/FiloSottile/mkcert) for a trusted chain). Include the
hostname clients dial as a SAN:

```bash
keytool -genkeypair -alias apollostorage -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore keystore.p12 -validity 825 \
  -dname "CN=apollo.lan" -ext "SAN=dns:apollo.lan,ip:192.168.1.20" \
  -storepass "$KEYSTORE_PASSWORD"

# export the cert for clients to trust
keytool -exportcert -alias apollostorage -keystore keystore.p12 \
  -storepass "$KEYSTORE_PASSWORD" -rfc -file apollostorage.crt
```

**2. Enable TLS + auth** via environment (keystore mounted read-only, secrets from
your secret store — see the commented block in `docker-compose.yml`):

| Env var | Purpose |
| --- | --- |
| `TLS_ENABLED` | `true` to serve HTTP/2 over TLS |
| `TLS_KEYSTORE_PATH` | path to the mounted PKCS#12 keystore |
| `TLS_KEYSTORE_PASSWORD` | keystore password (secret) |
| `AUTH_ENABLED` | `true` to require a bearer token on every object RPC |
| `AUTH_TOKENS` | comma-separated accepted tokens (secret) |

Misconfiguration fails fast: `AUTH_ENABLED=true` with no `AUTH_TOKENS`, or a missing
/ wrong-password keystore, aborts startup with a clear error. Tokens are compared in
constant time. The `grpc.health.v1.Health` service stays **unauthenticated** so
orchestrators can probe it without a token.

**3. Call the secured API** — trust the cert with `-cacert` and pass the token in an
`authorization` header:

```bash
# authenticated bucket creation over TLS
grpcurl -cacert apollostorage.crt -H 'authorization: Bearer my-secret-token' \
  -d '{"bucket":"media"}' apollo.lan:8443 apollostorage.grpc.ObjectApi/CreateBucket

# no/invalid token -> Unauthenticated
grpcurl -cacert apollostorage.crt -d '{"bucket":"media"}' \
  apollo.lan:8443 apollostorage.grpc.ObjectApi/CreateBucket

# health needs no token
grpcurl -cacert apollostorage.crt -d '{}' \
  apollo.lan:8443 grpc.health.v1.Health/Check
```

## Metrics & monitoring

ApolloStorage exposes Prometheus metrics at **`GET /metrics`** on the HTTP port
(`8080`), in the standard text exposition format. Collection is **on by default**;
set `METRICS_ENABLED=false` to disable it (the endpoint then returns `404`). Like
`/health`, `/metrics` is **unauthenticated** even when the API's TLS/auth are on — it
carries only operational telemetry, never bucket or object data.

```bash
curl -s localhost:8080/metrics | grep apollostorage_
```

Metric families:

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `apollostorage_grpc_requests_total` | counter | `method`, `status` | gRPC requests by RPC and outcome (`OK`, `NOT_FOUND`, `UNAUTHENTICATED`, …) |
| `apollostorage_grpc_request_duration_seconds` | histogram | `method` | gRPC request latency |
| `apollostorage_blob_operations_total` | counter | `operation`, `outcome` | blob put/get/delete by success/failure |
| `apollostorage_blob_operation_duration_seconds` | histogram | `operation` | blob operation latency (the disk/NFS hot spot) |
| `apollostorage_blob_bytes_total` | counter | `direction` | bytes read/written by the blob store |
| `apollostorage_build_info` | gauge | `version` | always `1`; carries the deployed version |
| `apollostorage_ready` | gauge | — | `1` when ready to serve, else `0` |
| `jvm_*`, `process_*` | — | — | standard JVM/process metrics |

Label values are drawn only from closed sets (RPC method names, fixed
operation/outcome/direction values) — bucket and object names are **never** used as
labels, bounding cardinality and avoiding information leakage.

Point Prometheus (the planned NAS-hosted stack) at the endpoint:

```yaml
scrape_configs:
  - job_name: apollostorage
    static_configs:
      - targets: ["apollo.lan:8080"]
```

## Reclaiming orphaned storage (blob GC)

A payload can be left on disk with no live object referencing it — an **orphan**. Overwrites
reclaim the superseded payload automatically, but a crash between persisting a payload and its
metadata event, or a failed delete, can still leave one behind (plus `.tmp` debris from aborted
writes). The **blob-gc** sweep reconciles the store against live state and reclaims them.

It is **off by default** and, when enabled, exposed as an admin endpoint on the HTTP port:

```bash
# dry run — reports what WOULD be reclaimed, deletes nothing
curl -sX POST localhost:8080/admin/blob-gc
# -> {"dryRun":true,"orphansFound":3,"bytesOrphaned":40961,"reclaimed":0, ...}

# confirmed — actually reclaim
curl -sX POST 'localhost:8080/admin/blob-gc?delete=true'
# -> {"dryRun":false,"reclaimed":3,"bytesReclaimed":40961,"tmpReclaimed":1, ...}
```

How it stays safe:

- **Dry-run by default** — deletion happens only with `?delete=true`, after you read the report.
- **Grace period** (`BLOB_GC_GRACE`, default 24h) — a blob is reclaimed only if it is unreferenced
  **and** older than the grace period, so a payload written just before its commit is never swept.
- **Authoritative live set** — the sweep asks each bucket entity directly (strongly consistent);
  a bucket it can't reach is skipped, never swept blind.
- **Manual trigger only**, gated by API auth when auth is enabled (pass the same
  `authorization: Bearer …`), and reclamation is best-effort (a failed unlink is counted, not fatal).

> **Invariant:** your **backup retention must outlive the grace period** (and your sweep cadence),
> so any blob the sweep reclaims has already been backed up. Tune `BLOB_GC_GRACE` accordingly.

## Running a cluster

ApolloStorage runs as a Pekko cluster (design D27–D33). A single replica forms a
**cluster of one** automatically (Cluster Bootstrap discovers its own management
endpoint), so `docker compose up` just works. Bucket entities are **cluster-sharded**
(one writer per bucket across the cluster) and the read-model projection is
distributed across nodes via `ShardedDaemonProcess`.

**Ports:** HTTP health (`8080`), gRPC (`8443`), Artery remoting (`CLUSTER_PORT`,
default `25520`), and Pekko Management/Bootstrap (`MANAGEMENT_PORT`, default `8558`).

**Multi-node:** run ≥3 replicas (keep-majority split-brain quorum) that share one
PostgreSQL. Each node needs a reachable remoting host and a discovery contact point:

| Env var | Purpose |
| --- | --- |
| `CLUSTER_HOST` / `CLUSTER_PORT` | this node's Artery remoting address (peers connect here) |
| `MANAGEMENT_HOST` / `MANAGEMENT_PORT` | management/bootstrap bind |
| `CONTACT_POINT_HOST` | the headless service name peers discover each other through |
| `CLUSTER_MIN_MEMBERS` | gate startup until N members are present (e.g. `3`) |
| `CLUSTER_NUMBER_OF_SHARDS`, `PROJECTION_INSTANCES` | sharding / projection sizing |

On Kubernetes, set `DISCOVERY_METHOD=kubernetes-api` and run behind a headless
service; Cluster Bootstrap forms the cluster from the pod set. A rolling restart is
graceful — Coordinated Shutdown hands off shards and rebalances projection instances
before a node leaves.

> **Caveat:** the journal + read model are a single shared PostgreSQL. The cluster
> scales the compute tier, not the database; **PostgreSQL HA is a future change**.

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
| `apollostorage.tls.enabled`                            | `TLS_ENABLED`             | `false`        |
| `apollostorage.tls.keystore-path`                      | `TLS_KEYSTORE_PATH`       | _(none)_       |
| `apollostorage.tls.keystore-password`                  | `TLS_KEYSTORE_PASSWORD`   | _(none)_       |
| `apollostorage.auth.enabled`                           | `AUTH_ENABLED`            | `false`        |
| `apollostorage.auth.tokens`                            | `AUTH_TOKENS`             | _(none)_       |
| `apollostorage.metrics.enabled`                        | `METRICS_ENABLED`         | `true`         |
| `apollostorage.blob-gc.enabled`                        | `BLOB_GC_ENABLED`         | `false`        |
| `apollostorage.blob-gc.grace`                          | `BLOB_GC_GRACE`           | `24 hours`     |
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
