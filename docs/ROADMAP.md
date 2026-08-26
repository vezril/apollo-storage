# Apollo Roadmap — Console Views & Storage Capabilities

Forward-looking design catalog for **ApolloStorage** and its console (**apollo-ui**). Two parts:
console **views** (mostly UI over endpoints Apollo already exposes) and storage **capabilities**
(backend features, each with a UI surface). Items are grounded in Apollo's actual architecture —
event-sourced catalog + CQRS read model, an S3/QuObjects blob store with random-UUID blob keys,
per-blob `crc32c`+`md5` computed on write, `generation`-based versioning, and blob-GC reconciliation.

This is a planning document, not a commitment. Each item becomes its own OpenSpec change when picked up.

**Status legend**
- ✅ **Ready** — data/endpoint already exists; UI-only work.
- 🔧 **Needs backend** — requires a new Apollo endpoint or read-model field.
- 🔬 **Needs research** — approach not yet decided.
- 🌐 **Cross-service** — depends on Hephaestus, HermesMQ, Redis, or the-lexicon.

---

## Part 1 — Console views (apollo-ui)

The sidebar currently has one view (**Buckets**). These fill it into a real operator console.

| View | What it shows | Data source | Status |
|---|---|---|---|
| **🏠 Overview** | Health, version, backend (s3/filesystem), bucket count, total objects & bytes, throughput/error snapshot | `/health` + `/metrics` + listings | ✅ Ready |
| **📊 Metrics** | Blob throughput (bytes r/w), op counts + p50/p95 latency (blob & gRPC), error rate | `/metrics` (Prometheus) | ✅ Ready |
| **🧹 Storage / Blob-GC** | Storage used, orphaned-blob count & bytes, "run reconciliation sweep" (write-scoped, confirm) | `POST /admin/blob-gc` → `{liveBlobs, orphansFound, bytesOrphaned, reclaimed}` | ✅ Ready |
| **🔎 Global object search** | Find objects across all buckets by name/prefix | Client fan-out over buckets now; thin search endpoint later | ✅ Ready / 🔧 |
| **ℹ️ About / Cluster** | Version, backend, S3 endpoint, TLS mode, sharding/node status, log levels | `/health` + small info endpoint | ✅ / 🔧 |
| **🌡️ Files by temperature** | Objects grouped Hot / Warm / Cold, per-bucket breakdown, storage saved by tiering, manual promote/demote | Access-tracking read model | 🔧 Needs backend (see §2.1) |
| **🕘 Object version history** | Prior `generation`s of an object + restore | Read model exposing generation history (lists current-only today) | 🔧 Needs backend |
| **📜 Activity / audit feed** | Recent puts/deletes/bucket ops | Events/audit endpoint (Apollo is event-sourced — natural, not yet exposed) | 🔧 Needs backend |
| **🔗 Trace lookup** | Look up a request by `correlationId`; recent errors | Log/trace query API (logs → stdout/Loki today) | 🔧 Needs backend |

**Recommended first two views:** **Storage / Blob-GC** and **Metrics** — both fully backed by
existing endpoints, both genuinely operator-facing.

---

## Part 2 — Storage capabilities

Each is a backend feature with a console surface. They share a few **foundations** (§3) — build those
first and several capabilities unlock together.

### 2.1 Access-temperature tiering (Hot / Warm / Cold) 🔧🌐

Classify every object by access temperature and act on it.

- **Hot** — accessed recently / frequently. Uncompressed, cache-eligible.
- **Warm** — occasional access. Uncompressed, on primary storage.
- **Cold** — not accessed in *N* days. Compression candidate (§2.2), optionally a cheaper storage class.

**Fits Apollo how:** the catalog tracks **no access time today** — only write-time `lastModified` from
the S3 listing. Tiering's foundation is **access tracking** (§3.1): record reads, aggregate a
`last_access_at` + a decaying access score per object into the read model. A periodic sweep (a HermesMQ
job) reclassifies objects and enqueues tier transitions.

**Design notes / open questions**
- Access events are **high volume** — do **not** put them in the event journal. Use a counter table or
  Redis counters with periodic rollup into the read model.
- Temperature is a *policy* over the score — keep thresholds configurable, not hard-coded.
- "Cheaper storage class" — does QuObjects expose S3 storage classes, or do we model cold as
  compression-only (§2.2) on the same bucket? (Research.)

**UI surface:** the **🌡️ Files by temperature** view (Part 1) — temperature badges, per-bucket
breakdown, bytes saved, manual promote/demote.

**Depends on:** §3.1 access tracking · §3.3 HermesMQ jobs · pairs with §2.2 and §2.3.

### 2.2 Compression of cold files 🔧

Transparently compress **Cold** blobs — trading CPU and first-access latency for space ("slower initial
access" is exactly the decompress-on-read cost).

**Fits Apollo how:** compression is per-blob and **transparent to the object API**. On demotion to Cold,
a worker compresses the blob (recommend **zstd** — strong ratio, fast decompress), writes a new blob,
swaps the `BlobRef`, and deletes the old one (the existing overwrite/reclaim path). On read of a cold
blob: decompress → serve → re-promote to Warm.

**Design notes / open questions**
- **Checksums are of the original content.** Store a codec tag + both *logical* (uncompressed) and
  *stored* (compressed) sizes on the catalog blob record; verify checksums against decompressed bytes.
- Atomicity: compress-to-new-blob then swap ref (never mutate a blob in place — blobs are immutable).
- Small files: compression overhead can exceed savings; gate by size, and consider a shared **zstd
  dictionary** for many small similar files (advanced).
- First-access latency budget — is a one-time decompress acceptable, or pre-warm on promotion?

**UI surface:** compression state + savings shown in the object metadata panel and the temperature view.

**Depends on:** §2.1 (defines "cold") · a codec/size field in the blob record · §3.3 worker.

### 2.3 Caching (Redis?) 🔬🌐

A read cache to cut S3 round-trips and cold-decompress cost for hot objects.

**Two layers**
1. **Metadata** — already fast (Postgres read model); caching gives marginal wins. Low priority.
2. **Blob bytes** — cache hot, small objects (and decompressed cold blobs on re-promotion). The real win.

**Options (the research)**
- **In-process LRU** — simplest, no new infra, but per-replica and lost on restart.
- **Redis** — shared across replicas, TTL/LRU eviction; costs memory + a new dependency. **Bonus:** Redis
  is also the natural home for the high-volume **access counters** in §2.1, so it may earn its keep
  serving double duty (cache + hotness counters).
- **Edge/CDN** — only if/when public reads exist (ties to the constellation access model).

**Open questions:** what to cache (bytes vs decompressed-cold vs metadata), size threshold, eviction
policy (temperature-aware LRU), **invalidation on overwrite/delete** (hook `ObjectDeleted`/supersede),
cache-stampede protection.

**Recommendation:** start with a **bounded in-process LRU** for small hot blobs; graduate to **Redis**
only when multi-replica sharing or the §2.1 counters justify the dependency.

**Depends on:** §2.1 (temperature-aware eviction) · optional Redis infra (Codex/GitOps).

### 2.4 Duplicate detection 🔧

Apollo already computes `crc32c` + `md5` per blob, so **detecting** identical content is nearly free —
the hashes are already in the catalog. The blobs, however, use **random-UUID keys**, so identical
content is currently stored **twice**.

**Two levels**
1. **Detection / reporting** (✅ easy) — index objects by content hash → group objects (across buckets)
   that share a hash. Read-only insight: duplicate groups, wasted bytes, dedup opportunity. Add
   **sha256** if the hash will drive anything destructive (md5 collisions are cheap to forge).
2. **Deduplication / reclamation** (🔧 hard) — **content-addressed blob storage**: key blobs by content
   hash and **refcount** them, so identical content is stored once. Requires: hash-keyed `BlobRef`s (a
   change from UUID), a refcount table, and blob-GC that only deletes at refcount 0 (extends the existing
   orphan sweep).

**UI surface:** a **Duplicates** view — duplicate groups, wasted bytes; later, one-click dedup.

**Depends on:** §3.2 content-hash index (detection) → content-addressed storage + refcounting (dedup).

### 2.5 Similar-image detection (Hephaestus) 🌐🔬

Beyond *exact* duplicates: perceptually **similar** images (resized, re-encoded, cropped, watermarked).
This is image *understanding* — squarely **Hephaestus's** domain (the media-processing service) — and
ties to the existing design-backlog "find-similar" and "ML auto-tagging" threads.

**Pipeline:** on image upload, Apollo emits a lifecycle event (via **HermesMQ**, using the
request-tracing/correlation envelope) → **Hephaestus** computes a **perceptual hash** (pHash/dHash) and/or
an **embedding** (e.g. CLIP) → stores it in a similarity index → "find similar" queries by Hamming
distance (pHash) or vector ANN (embeddings).

**Design notes / open questions**
- **pHash vs embeddings:** pHash is cheap and catches near-dupes (recompress/resize/crop); embeddings
  catch *semantic* similarity but need a vector store. Likely both: pHash for near-dupes, embeddings for
  "looks like".
- **Index:** `pgvector` (reuse Postgres) vs a dedicated vector DB (Qdrant/Milvus). Start with pgvector.
- **Where hashes live:** Hephaestus-owned store, or written back to Apollo's catalog? Contract via
  **the-lexicon** (a hash/embedding message + a find-similar RPC).
- Backfill for existing images vs new-uploads-only.

**UI surface:** a **"Find similar"** action on an image object → grid of near-matches with similarity
scores; plus a **Similar groups** view.

**Depends on:** 🌐 Hephaestus · HermesMQ · a vector/pHash index · the-lexicon contract. The largest,
most cross-service item — sequence it last.

---

## Part 3 — Foundations & sequencing

Build these first; each unlocks several capabilities.

1. **§3.1 Access tracking** — record reads → `last_access_at` + decaying access score in the read model
   (counters in a side-table or Redis, *not* the event journal). **Unlocks:** temperature view (Part 1),
   tiering (§2.1), temperature-aware cache eviction (§2.3).
2. **§3.2 Content-hash index** — index the existing `md5`/`crc32c` (add `sha256` if destructive).
   **Unlocks:** duplicate detection report (§2.4), then content-addressed dedup + refcounting.
3. **§3.3 Object-lifecycle events on HermesMQ** — emit put/delete/overwrite events (reusing the
   correlation-ID envelope already standardized). **Unlocks:** async workers for compression (§2.2),
   hashing, and the Hephaestus similar-image pipeline (§2.5); also the activity/audit feed view.
4. **§3.4 Redis (optional infra)** — cache (§2.3) + high-volume access counters (§2.1). A Codex/GitOps
   deploy decision.
5. **§3.5 Hephaestus + vector index** — similar images (§2.5).

**Suggested order:** Metrics + Blob-GC views (ready now) → access tracking → temperature view + tiering →
cold compression → dedup detection → caching → dedup reclamation → similar images.

---

## Cross-service asks (for Codex to route)

- **Hephaestus:** perceptual-hash / embedding computation + a similarity index; contract via the-lexicon.
- **HermesMQ:** an Apollo object-lifecycle topic (feeds compression, hashing, similar-image workers).
- **Infra (GitOps):** a Redis instance if §2.3/§3.4 graduate past in-process.
- **the-lexicon:** messages/RPCs for hashes, embeddings, and find-similar.
