## Why

Every byte Apollo serves is fetched from scratch, every time. The object API sets no `ETag`, no
`Cache-Control`, and no `Last-Modified`, so a client has no way to reuse what it already holds and no
way to ask "has this changed?" — the only available question is "send it again."

Measured on the live cluster (single 964-byte thumbnail, repeated, via port-forward):

| Request | Latency |
|---|---|
| `HEAD` — metadata only, read model, no blob fetch | **~20 ms** |
| `GET` — same object, adds the QuObjects S3 fetch | **~53 ms** |

The gap is the point: **~33 ms of every object read is an S3 round-trip that a validator could have
skipped.** For a caller re-rendering the same media — a gallery, a re-visit, a back-navigation —
that cost is paid in full on every request, for bytes the caller already has.

Apollo is unusually well-positioned to fix this cheaply. It already computes and stores a `generation`
and an `md5` for every object, and `ObjectOperations.headObject` already resolves that metadata
*without* touching the blob store. A conditional request therefore needs no new plumbing: resolve
metadata, compare, and answer `304` — at HEAD cost, never opening an S3 stream.

## What Changes

- **`ETag` on object `GET` and `HEAD`**, derived from the object's existing `generation` and `md5`.
  No new computation and no extra I/O — both values are already in the metadata being returned.
- **Conditional requests**: `If-None-Match` on `GET`/`HEAD` answers **`304 Not Modified`** when the
  validator matches, resolved from metadata alone so the blob is never fetched. `If-Match` guards
  are **not** in scope (see below).
- **`Cache-Control` that tells the truth about mutability.** Apollo object URLs are **mutable by
  design** — an overwrite increments the generation at the *same* path. So object responses declare
  revalidate-before-reuse (`no-cache`), **not** `immutable`. Freezing them would serve stale bytes
  after an overwrite; this change deliberately does not do that.
- **Error responses are never cached.** A `404` for an object that is still being written must not
  be remembered, or the object stays invisible after it appears.
- **Documentation assets get their own treatment** (secondary): `/docs` currently re-sends ~1.5 MB of
  Swagger UI JavaScript on every page load. The asset paths do not carry the webjar version, so they
  are revalidated rather than frozen; the OpenAPI document is validated the same way.

## Capabilities

### Modified Capabilities
- `rest-object-api`: object `GET`/`HEAD` gain an `ETag` and a `Cache-Control` directive, and honour
  `If-None-Match` by answering `304` without reading the blob.
- `api-docs-portal`: the documentation page's static assets and the served OpenAPI document become
  cacheable-with-revalidation instead of re-sent in full on every load.

## Impact

- **Code**: `server/.../http/ObjectRoutes.scala` (validator + conditional handling on the object
  routes), `server/.../http/DocsRoutes.scala` (asset caching). No change to `ObjectOperations`, the
  blob stores, the domain, or the read model — the metadata-only seam this relies on already exists.
- **APIs**: additive and backward-compatible. A client that ignores `ETag` sees exactly today's
  behaviour; only a client that sends `If-None-Match` can receive a `304`.
- **gRPC**: unaffected. HTTP cache semantics have no gRPC equivalent, and this change does not invent
  one.
- **Risk**: the failure mode of a caching bug is *serving stale bytes*, which is worse than being
  slow. The mitigation is that the validator is derived from `generation` + `md5` — an overwrite
  changes both, so a stale validator cannot match new content.

## Explicitly Not In Scope

- **The Artemis media gateway.** The larger win lives there — `/media/{md5}/{variant}` is
  content-addressed, so it can be frozen outright, and it is the path a browser actually uses for
  images (measured at ~84 ms per thumbnail end-to-end). That work belongs to `artemis-service` and
  its owning session, not to this repository.
- **`immutable` / long `max-age` on object URLs.** Not correct for mutable paths, as above.
- **Any server-side cache** — no in-process LRU, no Redis. This change adds no state and no memory
  footprint; it only lets clients avoid asking again. Whether a server-side cache is ever warranted
  is a separate question the roadmap holds open, and one this change may well answer by removing the
  traffic that would have justified it.
- **Range requests / `206`.** Apollo's REST API serves whole bodies only today. Partial content is a
  distinct capability, and mixing it into a caching change would blur both.
