## Context

Apollo's HTTP object API sends no cache validators. A client that already holds an object has no way
to say "only if it changed," so every read re-fetches the payload in full.

Two facts from the code shape this design:

1. **`ObjectOperations.headObject` resolves metadata from the read model alone** — no blob store call
   at all. Measured at **~20 ms** against the live cluster.
2. **`S3BlobStore.get` is not free before the bytes flow.** It performs an eager
   `S3.getObjectMetadata` round-trip and *then* returns a lazy `S3.getObject` source. So calling
   `getObject` costs an S3 hop even if the body is never materialised — measured at **~53 ms** total
   for the same object.

That ~33 ms delta is what a conditional request can avoid, and it is only avoidable if the validator
is compared **before** `getObject` is called. Checking afterwards would pay the S3 hop and then throw
the answer away.

## Goals / Non-Goals

**Goals:**

- A caller holding an object can revalidate it cheaply and get `304` without any blob-store traffic.
- Cache directives that are *true* for a mutable object store — no promise Apollo cannot keep.
- Zero regression for callers that send no conditional header.
- Stop re-sending ~1.5 MB of documentation assets on every `/docs` load.

**Non-Goals:**

- Any server-side cache (in-process or Redis). This change stores nothing and adds no memory.
- `immutable` or long `max-age` on object URLs — incorrect for paths whose content can be replaced.
- Range requests / `206`. Apollo serves whole bodies; partial content is a separate capability.
- `If-Match` write preconditions (optimistic concurrency). Valuable, but that is write semantics, not
  caching — mixing them would blur both. See Open Questions.
- The Artemis media gateway, which owns the larger win and belongs to another repository.

## Decisions

### D1 — The validator is the object's md5, as a strong ETag

`ETag: "<md5>"`, using the md5 Apollo already stores per object.

*Why md5 alone, rather than including the generation:* an ETag identifies a **representation**, not a
version counter. If an object is overwritten with byte-identical content, the generation increments
but the bytes do not — and a client's cached copy is genuinely still valid. An md5-derived validator
returns `304` there, which is correct *and* saves a pointless re-transfer. Folding the generation in
would force a re-download of bytes the client already has.

It is a **strong** validator because it is computed over the exact bytes, which is what strong means.

*Known edge:* overwriting with identical bytes but a different declared content-type yields the same
validator, so a client could keep the old content-type until it re-requests unconditionally. This is
rare, low-harm, and the alternative (hashing metadata into the validator) buys little for the
complexity. Documented rather than engineered around.

*Alternatives considered:* `"<generation>-<md5>"` — rejected per above. `crc32c` — also stored, but a
32-bit checksum is a weaker discriminator than md5 for no benefit. `Last-Modified` — Apollo's read
model does not carry a reliable per-object modification timestamp, and second-granularity timestamps
are a worse validator than a content hash.

### D2 — `Cache-Control: private, no-cache` on object responses

*Why `no-cache`:* the directive is widely misread — it does **not** mean "do not store." It means
*store it, but revalidate before reuse*, which is exactly right for a URL whose content can be
replaced. Apollo object paths are mutable by design: an overwrite increments the generation at the
same path. Anything with a positive `max-age` would let a client serve superseded bytes for the
duration of the window, and `immutable` would be an outright false promise.

*Why `private`:* object reads are bearer-authenticated when auth is enabled, and a shared cache must
never retain an authenticated response for another identity. It costs nothing today (no shared proxy
sits in the path) and it stays correct if an authenticating edge is introduced later.

*Alternative considered:* a small `max-age` (a few seconds) to absorb burst re-reads. Rejected for
v1: it trades correctness for a narrow win and introduces a window in which Apollo knowingly serves
stale bytes. A configurable window is a later decision, made with evidence.

### D3 — Conditional handling short-circuits *only* when a validator is present

The route reads `If-None-Match` first:

```
  If-None-Match absent  ──▶ getObject (unchanged path, byte-for-byte today's behaviour)

  If-None-Match present ──▶ headObject  (read model, ~20 ms, no S3)
                              │
                              ├── matches ──▶ 304, no body, no blob-store call
                              └── differs ──▶ getObject ──▶ 200 + body
```

*Why gate on the header's presence:* a conditional miss resolves metadata twice — once for the
comparison, once inside `getObject`. Rather than restructure the facade to thread a validator through
it (pushing an HTTP concern into a transport-neutral seam), the extra lookup is accepted **and
confined to conditional requests**. Unconditional reads take exactly the path they take today, so the
common case cannot regress.

The duplicated lookup is a read-model query, small next to the S3 round-trip it may avoid. If it ever
shows up in the metrics, the fix is a facade method that takes an optional validator — deliberately
not done now, because it would complicate a seam to solve a cost not yet observed.

### D4 — Error responses are explicitly non-storable

A `404` carries `Cache-Control: no-store`.

This is the failure mode most likely to bite in practice: a client requests an object while it is
still being written, receives `404`, and — if that response is remembered — never sees the object
appear. Absent an explicit directive, a cache is permitted to apply heuristic freshness to responses
that carry none. Saying `no-store` removes the question.

### D5 — Documentation assets are validated, not frozen

Swagger UI's assets are served at version-less URLs (`/docs/swagger-ui.css`) while the bytes come from
a version-pinned classpath path. The URL therefore *is* mutable across an upgrade, so freezing it
would pin browsers to a stale UI.

**Amended during implementation.** The original decision was to derive the validator from the pinned
webjar version. Implementation showed pekko's resource directives *already* emit an `ETag` and answer
conditional requests — most of this requirement was satisfied before any code was written. Their
validator is `f(length, lastModified)` (e.g. `"4eba4197ae0199f0"`; the shared suffix across assets is
the jar timestamp, fixed by native-packager, so validators are stable across rebuilds).

Overriding it would mean abandoning `getFromResource` — it sets its own `ETag`, so a second one would
duplicate the header — and hand-rolling classpath reading plus conditional handling. That replaces
framework-tested code to close a gap that requires two swagger-ui releases with **byte-identical file
lengths**, whose consequence is a stale docs page until cache eviction.

Decision: keep pekko's validator and require only that it changes when the bytes change. The residual
risk is named rather than engineered around. What this change adds is the missing half — the
`Cache-Control` directive, which pekko does not supply.

With `no-cache` + a validator, a repeat `/docs` load becomes a conditional request answered `304`
with an empty body instead of ~1.5 MB of JavaScript. The unauthenticated docs endpoints may use
`public`, since they expose only the API's shape.

## Risks / Trade-offs

- **A caching bug serves stale bytes — worse than being slow.** → The validator derives from the
  content hash, so an overwrite necessarily changes it; a stale validator cannot match new content.
  Directives are conservative by construction (`no-cache`, never `immutable`).
- **Content negotiation / compression by an intermediary.** A proxy that gzips a response should not
  reuse a strong validator across encodings. → Apollo does not compress, and its media types are
  already-compressed formats in practice; noted so a future compression change revisits this.
- **Conditional misses cost one extra read-model lookup.** → Confined to requests that opt in by
  sending a validator (D3); unconditional reads are untouched.
- **`private` blocks shared-cache reuse that `public` would allow.** → Accepted deliberately: there is
  no shared cache in the path today, and `private` is the directive that survives an auth edge.

## Migration Plan

Purely additive and backward-compatible; no data, schema, or configuration change. A client that
ignores `ETag` observes today's behaviour exactly. Rollback is redeploying the prior image — nothing
is persisted, so there is no state to unwind.

## Open Questions

- **`If-Match` on writes** would give real optimistic concurrency (reject an overwrite whose
  generation moved underneath the caller). It reuses the same validator machinery but changes write
  semantics, so it is deferred rather than smuggled in here. Worth its own change.
- **A short `max-age` window** on object reads, if revalidation traffic ever proves to be the cost —
  decided with metrics, not in advance.
- **Whether listing endpoints should say anything.** They are eventually consistent and change often;
  leaving them silent risks heuristic caching by an intermediary, while marking them `no-store` is
  arguably scope creep. Left open pending a view on whether any real client would cache them.
