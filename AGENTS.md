# AGENTS.md — orientation for an AI session picking up ApolloStorage

You are almost certainly Claude, opening this repository with no memory of it. This file exists so
you do not have to re-derive the project from the source tree. Read it first, then go to the
authoritative artifacts it points at.

**It is not a substitute for the specs.** Where this file and `openspec/specs/` disagree, the specs
win — and fixing the drift is part of your change.

---

## 1. What Apollo is

A self-hosted, GCS-inspired **object storage service** for a production homelab. Buckets and
objects, an event-sourced CQRS catalog, and payload bytes in a pluggable blob store. It is not a
toy: it runs continuously, must survive restarts and storage outages without corrupting the
catalog, and is depended on by other services in the "Codex constellation" (Artemis catalogs
media whose bytes live here; Hephaestus writes derivatives here).

Wire compatibility with Google's clients is an explicit **non-goal**. GCS is a naming and semantics
reference only.

## 2. Architecture in one pass

```
   gRPC (ObjectApi)  ─┐                        ┌─→ BucketEntity (event-sourced, cluster-sharded)
                      ├─→ ObjectService ──────→┤        │ persists events
   REST (/v1/…)      ─┘   (shared core)        │        ▼
                                               │   Postgres journal (r2dbc)
   HTTP also serves: /health /metrics                    │
                     /admin/blob-gc /docs               ▼ projection
                                               │   read-model tables ──→ list/queries
                                               └─→ BlobStore ──→ filesystem | S3 (QuObjects)
```

- **Two adapters, one core.** gRPC and REST are both thin adapters over the same
  `ObjectService`/entity path, so they share generations, checksums, and side effects. A behaviour
  change belongs in the core, not in one adapter.
- **Bucket-as-entity.** Each bucket is one `EventSourcedBehavior` that serializes writes and owns
  its generation counters. Entity ids are structured (`bucket|<name>`) so a future shard split is
  an identity change, not a redesign.
- **Payloads never enter the journal.** Bytes stream to the blob store; only a checksum-verified
  write commits an `ObjectCreated` event.
- **Reads are eventually consistent.** Listings come from projection tables, so a just-written
  object can be absent for a moment. This is by design — do not "fix" it with a synchronous read.

**Modules:** `core/` = pure domain (no Pekko, no I/O — keep it that way). `server/` = actors,
persistence, blob store, HTTP/gRPC, projections, `Main.scala`.

## 3. Invariants — do not break these

1. **Blobs are immutable.** Never mutate a stored blob in place. Overwrites write a new blob and
   swap the reference; the superseded one is reclaimed (or left for blob-GC).
2. **Checksums are computed on the way through**, in one pass, and verified before an event is
   persisted. A mismatch stores nothing.
3. **The journal is append-only and its events are serialized with CBOR.** Changing an event's
   shape is a compatibility problem, not a refactor — old events must still deserialize.
4. **`core/` stays pure.** Domain errors are `Either`/ADTs, not exceptions; no I/O, no clock, no
   randomness. Effects live behind traits in `server/`.
5. **Auth is scoped.** Reads accept a read token, mutations require write. `/health`, `/metrics`,
   and `/docs` are deliberately unauthenticated — they expose no data.
6. **Every request carries a correlation id.** Minted at the edge, in the MDC, echoed as
   `X-Correlation-Id`, and included in error bodies. Don't drop it across an async hop — use the
   MDC-propagating execution context.

## 4. How work is done here — non-negotiable

**TDD.** Red → green → refactor, for every behaviour. Tests are written first and run after each
step. A task is not done until its tests pass. This is stated in `openspec/project.md` and it is
enforced socially, not by CI — do not skip it because CI wouldn't notice.

**Spec-driven via OpenSpec.** Features go through a change:

```bash
openspec new change "<name>"          # or: /opsx:propose
# artifacts: proposal.md → design.md + specs/ → tasks.md
# then: /opsx:apply   (implement, ticking tasks)
# then: /opsx:archive (sync delta specs into openspec/specs/)
```

Specs use `### Requirement:` (SHALL/MUST) and `#### Scenario:` — **exactly four hashes**, or the
scenario is silently ignored. `openspec validate --changes` checks the format.

**Branches and releases.** `feature/*` (or `docs/*`) → PR into **`development`** → PR into
**`main`**. `main` is protected: never push to it directly. A release is an annotated tag
`vX.Y.Z` **on main**, which publishes `calvinference/apollostorage:X.Y.Z` to Docker Hub.
Semver image tags are immutable; only `:latest` moves.

**Ask before merging or tagging.** Calvin drives those decisions. Build and open the PR; do not
land it on your own initiative.

## 5. The API surface

| Surface | Where | Auth |
|---|---|---|
| gRPC `ObjectApi` | port **8443** (h2c) — contract is protobuf in **the-lexicon**, not this repo | scoped bearer |
| REST object API | `/v1/buckets…` on port **8080** | scoped bearer |
| Health | `GET /health` | none |
| Metrics | `GET /metrics` (Prometheus) | none |
| Blob GC | `POST /admin/blob-gc?delete=` | **write** scope |
| API docs | `GET /docs` (Swagger UI), `GET /docs/openapi.yaml` | none |

Also on the pod: Pekko Management **8558**, Artery remoting **25520** (internal).

**Authoritative artifacts:** [`docs/rest-api.openapi.yaml`](docs/rest-api.openapi.yaml) is the HTTP
contract — it is packaged into the jar at build time and served at `/docs/openapi.yaml`, so the
served document cannot drift from the reviewed one. [`insomnia/`](insomnia/) is the runnable
collection covering the same surface.

**Cache semantics on the object API** (add-http-caching). Three rules a change must not silently
break:

1. **The validator is the object's md5**, exposed as a strong `ETag`. Not the generation — an
   overwrite with byte-identical content leaves the client's copy current, and an md5 validator
   correctly answers `304` there.
2. **Object URLs are never `immutable` and never carry a positive `max-age`.** A path is mutable by
   design (an overwrite increments the generation at the same path), so responses are
   `private, no-cache` — store, but revalidate.
3. **Errors are `no-store`.** A `404` for an object still being written must not be remembered, or
   the object never appears for that client.

`If-None-Match` is compared *before* `getObject`, because `BlobStore.get` costs an S3 round-trip
before any byte flows; a `304` must resolve from `headObject` alone. `ObjectCachingSpec` asserts the
blob store is untouched on a `304` — if you refactor that path, keep that assertion meaningful.

⚠️ **Adding an HTTP route obliges you to do three things in the same change:** document it in the
OpenAPI file, add it to the inventory in `DocumentedSurfaceSpec`, and add a request to the Insomnia
collection. The spec test enforces the first two against each other, but nothing can force you to
extend the inventory — that residual gap is deliberate and named in the docs-portal design. This is
how the documentation fell a release behind once already.

## 6. Configuration

HOCON with env overrides (`server/src/main/resources/application.conf`). The ones that matter:

| Var | Notes |
|---|---|
| `BLOB_BACKEND` | `filesystem` \| `s3` — selects the blob store |
| `S3_ENDPOINT` / `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` | S3 backend; path-style is on by default |
| `S3_TLS_INSECURE` | escape hatch for a bad cert — see known gaps |
| `BLOB_STORE_PATH` | filesystem backend root |
| `AUTH_ENABLED`, `AUTH_TOKENS`, `AUTH_PRINCIPALS` | scoped bearer tokens |
| `TLS_ENABLED`, `TLS_KEYSTORE_PATH`, `TLS_KEYSTORE_PASSWORD` | TLS for the listeners |
| `HTTP_PORT`, `GRPC_PORT`, `HTTP_HOST` | listeners |
| `BLOB_GC_ENABLED`, `BLOB_GC_GRACE` | blob GC (route is 404 when disabled) |
| `METRICS_ENABLED`, `LOG_LEVEL`, `LOG_LEVEL_APOLLO` | observability |
| `DB_AUTO_MIGRATE` | schema self-migration at boot |

## 7. Deployment reality

- **Cluster:** single-node **k3s v1.21** on a QNAP NAS (`mimir`). There is **no Flux** — deploys are
  a manual `helm upgrade` with mirrored values, driven from the **codex** GitOps repo (`apps/apollo`).
  Do not write instructions that assume Flux reconciles anything.
- **Blob backend in production:** **QuObjects** (the NAS's S3 service), bucket `apollo-blobs`,
  path-style. All Apollo blobs live in that one bucket, keyed `<bucket>/<2-char shard>/<uuid>` —
  so a logical Apollo bucket is a key prefix, not an S3 bucket, and creating a bucket writes nothing
  to S3. Only object uploads do.
- **Access:** LAN, plus Tailscale. The operator console (**apollo-ui**, a separate repo) is at
  `http://apollo.tailscale:61642` via the shared Traefik NodePort (host-header routed).
- **Sibling sessions own sibling repos.** apollo-ui, artemis-*, hephaestus, codex each have an
  owning Claude session. Announce/route work through the owner before opening PRs into their repo.

## 8. Current state and known gaps

*(as of 2026-08-25 — update this section when it stops being true)*

- Released **v0.12.1**; deployed and verified against live QuObjects (REST + gRPC round-trips).
- Recent changes: request tracing (v0.11.0), S3 backend + REST API (v0.12.0), k8s discovery fix
  (v0.12.1). History lives in `openspec/changes/archive/`.

Known gaps, all deliberate:

- **`S3_TLS_INSECURE=true` in production.** QuObjects' certificate lacks a matching SAN. The fix is
  regenerating the cert, not keeping the flag. Flagged, not forgotten.
- **Discovery pod-label selector is hard-coded** to the chart's labels; a `POD_LABEL_SELECTOR` knob
  is a filed follow-up. A mismatch here silently prevents the cluster from forming.
- **The documented-surface inventory is manual** (see §5).
- **`docs/ROADMAP.md`** holds the forward plan: console views, access-temperature tiering, cold-file
  compression, caching, duplicate detection, similar images. Note that dedup and similar-image work
  is designed to live at the **Artemis** layer, not here — Apollo stays the blob substrate.

## 9. Where things live

| Path | What |
|---|---|
| `core/src/main/scala/apollostorage/domain/` | pure domain — entity decide/apply, events, value types |
| `server/src/main/scala/apollostorage/Main.scala` | wiring: config → stores → routes → binds |
| `server/…/http/` | REST routes, health, metrics, admin, docs portal, tracing directive |
| `server/…/blob/` | `BlobStore` trait, filesystem + S3 implementations, blob GC |
| `server/…/persistence/`, `…/projection/` | sharding, migration, read-model projections |
| `openspec/specs/` | **the authoritative behaviour specs** |
| `openspec/changes/` | in-flight changes; `archive/` is the decision history |
| `openspec/project.md` | conventions (TDD, style, architecture rules) |
| `docs/` | OpenAPI contract, roadmap |
| `scripts/verify-docs.sh` | documentation checks run in CI |

## 10. Keeping this file true

**Update `AGENTS.md` in the same change** that alters any of: architecture or invariants, the API
surface, the workflow/branching/release process, configuration, the deployment picture, or the
current-state section in §8. It is a task in your change, not a later cleanup — a handoff file that
lies is worse than none, because the next session will trust it.

Record what is actually true, including gaps and deferred work. Do not duplicate what the specs or
README already own; link to them. `scripts/verify-docs.sh` checks that the paths referenced here
still resolve, but nothing checks the prose — that part is on you.
