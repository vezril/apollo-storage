## 1. Validator and directives on object reads (tests first)

- [x] 1.1 Test: `GET` of an object returns `200` with a strong `ETag` derived from its stored md5
- [x] 1.2 Test: `HEAD` and `GET` of the same object return the **same** `ETag`
- [x] 1.3 Test: overwriting an object with **different** content changes the `ETag`
- [x] 1.4 Test: overwriting with **byte-identical** content leaves the `ETag` unchanged (design D1 —
      the client's representation is still current, so a re-transfer would be pointless)
- [x] 1.5 Test: object responses carry `Cache-Control: private, no-cache` — and assert the negative,
      that neither `immutable` nor a positive `max-age` is present (design D2)
- [x] 1.6 Implement the validator + directives on the object `GET`/`HEAD` routes in `ObjectRoutes`
- [x] 1.7 Run 1.1–1.5 — green

## 2. Conditional reads and the 304 fast path (tests first)

- [x] 2.1 Test: `GET` with a **matching** `If-None-Match` returns `304`, no body
- [x] 2.2 Test: `HEAD` with a matching `If-None-Match` returns `304`
- [x] 2.3 Test: `GET` with a **stale** `If-None-Match` returns `200` with current content and the
      current `ETag`
- [x] 2.4 Test: **the 304 path performs no blob-store read** — assert against a `BlobStore` test
      double that records calls (this is the whole point of the change; without this assertion the
      feature could "pass" while still paying the S3 hop)
- [x] 2.5 Test: `If-None-Match` naming a **missing** object returns `404`, not `304`
- [x] 2.6 Test: an **unconditional** read is byte-for-byte unchanged — same status, body, and
      `X-Apollo-*` headers as before (design D3: the common path must not regress)
- [x] 2.7 Test: a malformed / unparseable `If-None-Match` is ignored rather than fatal — the request
      is served normally
- [x] 2.8 Implement conditional handling, resolving the comparison via `headObject` **before** any
      `getObject` call, and only when `If-None-Match` is present
- [x] 2.9 Run 2.1–2.7 — green

## 3. Error responses are non-storable (tests first)

- [x] 3.1 Test: a `404` for a missing object carries `Cache-Control: no-store` (design D4 — the
      read-before-write case that would otherwise leave an object permanently invisible)
- [x] 3.2 Implement `no-store` on the object-API error responses
- [x] 3.3 Run 3.1 — green

## 4. Documentation assets (tests first)

- [x] 4.1 Test: each `/docs` asset returns an `ETag` that includes the pinned webjar version
- [x] 4.2 Test: `/docs/openapi.yaml` returns an `ETag` derived from the document's content
- [x] 4.3 Test: a matching `If-None-Match` on an asset and on the OpenAPI document returns `304`
      with no body
- [x] 4.4 Test: asset and page responses require revalidation (no positive `max-age`), so an upgrade
      is picked up on the next visit rather than after an expiry window (design D5)
- [x] 4.5 Implement validators + directives in `DocsRoutes`; compute the OpenAPI document's hash once
      at startup, not per request
- [x] 4.6 Run 4.1–4.4 — green

## 5. Documented surface stays honest

- [x] 5.1 Document the new response semantics in `docs/rest-api.openapi.yaml`: the `ETag` response
      header, the `If-None-Match` request header, and the `304` response on object `GET`/`HEAD`
- [x] 5.2 Verify the document still parses and that `DocumentedSurfaceSpec` passes (no new *paths*
      are added, so the surface inventory is unchanged — confirm rather than assume)
- [x] 5.3 Add a disabled `If-None-Match` header to the Insomnia "Download object" request so the
      conditional flow is discoverable from the collection
- [x] 5.4 Run `bash scripts/verify-docs.sh` — passes

## 6. Prove the win is real (not just green tests)

- [x] 6.1 Run the full suite (`sbt test`) and `sbt scalafmtCheckAll` — both clean
- [x] 6.2 Against a running instance, measure and record: unconditional `GET`, conditional `GET`
      returning `304`, and `HEAD`. **Acceptance: the 304 latency tracks `HEAD` (~20 ms), not `GET`
      (~53 ms).** A 304 that costs as much as a GET means the short-circuit is in the wrong place
- [x] 6.3 Confirm from the S3/blob metrics (`apollostorage_blob_operations_total`) that a 304 issues
      **no** blob operation
- [x] 6.4 Sanity-check a browser against `/docs`: a reload issues conditional requests answered `304`
      rather than re-transferring the ~1.5 MB asset payload

## 7. Handoff obligations

- [x] 7.1 Update `AGENTS.md`: the object API now has cache semantics — record the validator rule
      (md5-derived), the mutability constraint (never `immutable` on object URLs), and the
      never-cache-a-404 rule, so a future change does not silently break them
- [x] 7.2 Note in `docs/ROADMAP.md` that the caching entry's first tier is addressed here for
      Apollo's own surface, that the **gateway** half remains with `artemis-service`, and that any
      server-side cache (LRU/Redis) should be re-argued **after** this lands — it may remove the very
      traffic that would have justified it
