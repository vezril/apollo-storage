# Change: add-access-tracking

> Record **when** each object was last read and how often, so that temperature-aware features
> (tiering, promotion/demotion, cold compression, temperature-aware cache eviction) have evidence
> instead of a guess. Starts §3.1 in `docs/ROADMAP.md`.

## Why

The catalog currently tracks `updated_at` — a *write* time — and no read activity at all. "This
object has not been touched in six months" cannot be answered. That is also the foundation several
unrelated roadmap items wait on, and each of them has to re-answer "how do we know?" without it.

Doing this early, before compression or tiering, keeps the invariant clean: reads are observed in
exactly one way, and every policy on top is a function of the same two numbers.

## What Changes

New capability **`access-tracking`**: object reads are observed, aggregated, and reflected in the
read model as `last_access_at` and a decaying `access_score`. A policy can then look at either or
both without the policy having to know how they were produced.

## The three decisions this change rests on

**Where recording lives.** A thin `AccessRecorder` abstraction invoked at both read adapters (REST
and gRPC). AGENTS.md §2 is explicit that a behaviour change belongs in the core rather than an
adapter, and the ideal here is a single shared read path; that refactor is real debt and a separate
change. This change does **not** paper over it — the two call sites are a known duplication and the
suite enforces the shared recorder is hit from both.

**What counts as a read.** `GET`, `HEAD`, and `304 Not Modified`. A 304 means the client is actively
using its cached copy of *this* object, so excluding it would make cached-and-popular objects look
cold. Error reads (`404`, `401`, `403`) do not count; a never-successful probe is not access.

**Write strategy: buffered, flushed periodically.** The catalog is low-write today, and making
*every* object GET a database write would be a large write-amplification change for little gain.
Hits are counted in memory, keyed by `(bucket, object)`, and flushed in batches every N seconds; a
flush combines them into one UPDATE per object (`access_count += n`, `last_access_at := max(…)`,
score recomputed). What this trades away is named up front: a crash loses the current in-memory
window — bounded, documented, and acceptable, since the worst case is an object that looks slightly
colder than it is.

## Impact

- `core/`: value additions (`AccessEvent`, score computation) — pure, no I/O.
- `server/`: new `AccessRecorder` service + a `BufferedAccessRecorder` implementation; wiring in
  `Main.scala`; invocations in the REST `ObjectRoutes` read paths and the gRPC `ObjectApiImpl` read
  paths (including the 304 branch); schema migration adding `object_index.last_access_at` and
  `object_index.access_count` (and an index on `last_access_at` for sweep queries).
- Read model: the two new columns are returned on existing object reads and in the listing API,
  nullable until the first access is recorded, so a cold existing object does not look "zero
  accesses" versus "unknown".
- Blob store: untouched. This is a catalog-side observation, not a blob-side one.
- Metrics: a counter for buffered-hits-flushed and a gauge for current buffer depth.
- **Not in this change** (explicit non-scope): the decaying score formula is included because the
  spec needs a definition, but **temperature classification** (Hot/Warm/Cold thresholds), the
  sweep/promotion logic, and the console view belong to §2.1 and Part 1 of the roadmap. This change
  gives them inputs; it does not act on them.

## Verification

- Core: property tests on the score recomputation.
- Server: unit tests for the recorder's buffering and flush; integration tests covering (a) a REST
  GET bumps `last_access_at` and `access_count` via the flush, (b) a HEAD does too, (c) a 304 does
  too, (d) a 404 does not, (e) both adapters hit the recorder (so neither call site can be quietly
  removed), and (f) a flush failure does not block reads.
