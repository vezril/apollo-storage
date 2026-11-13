# Tasks — add-access-tracking

TDD per AGENTS.md §4. Red → green → refactor, tests first.

## 1. Domain (core)

- [ ] 1.1 `AccessEvent` value + the decay/score function as a pure method in `core/`
- [ ] 1.2 Property tests: score strictly increases on a hit, strictly decreases with time alone,
  commutative over hits in the same instant

## 2. Schema

- [ ] 2.1 Migration adds `last_access_at timestamp with time zone NULL`, `access_count BIGINT NOT
  NULL DEFAULT 0`, `access_score DOUBLE PRECISION NOT NULL DEFAULT 0` to `object_index`; plus an
  index on `last_access_at` for the sweep queries that §2.1 will add
- [ ] 2.2 Statements idempotent (`ALTER TABLE ... IF NOT EXISTS` style via a conditional DO block);
  no statement contains a semicolon in a string (the splitter is naive)
- [ ] 2.3 Projection: on `ObjectCommitted` for an overwrite (generation > 1), clear access fields;
  on `ObjectDeleted`, drop the row (already happens — cover with a test)

## 3. Recorder

- [ ] 3.1 `AccessRecorder` trait in `server/`: `record(bucket, key, when)`; no return value, must
  not throw
- [ ] 3.2 `BufferedAccessRecorder` impl: in-memory `ConcurrentHashMap[(bucket, key), accumulator]`
  with hit-count + max-timestamp per entry, plus a timer-driven flush
- [ ] 3.3 Flush: coalesce entries into one UPDATE per object via a single batched statement; log
  and retry on failure, do not drop silently
- [ ] 3.4 Bounded buffer: on overflow, drop oldest + increment `access_recorder_dropped_hits`
- [ ] 3.5 `NoopAccessRecorder` for unit tests that do not care

## 4. Wiring

- [ ] 4.1 `Main.scala`: construct one `AccessRecorder`, pass it down to the HTTP and gRPC layers
  (configurable flush interval + buffer cap via `application.conf`, sensible defaults)
- [ ] 4.2 REST `ObjectRoutes`: record on a successful `GET` (200), on `HEAD` (200), on the `304`
  branch; do NOT record on 404/401/403
- [ ] 4.3 gRPC `ObjectApiImpl`: record on a successful object read; mirror the same rules
- [ ] 4.4 Read model + listing responses return `lastAccessAt`, `accessCount`, `accessScore`

## 5. Metrics

- [ ] 5.1 `access_recorder_hits_buffered`, `access_recorder_hits_flushed`,
  `access_recorder_dropped_hits`, gauge `access_recorder_buffer_size`

## 6. Verify

- [ ] 6.1 Six scenarios from the spec pass as integration tests — including the "both adapters
  record" one, which is what prevents a quiet refactor from removing one call site
- [ ] 6.2 215 existing tests remain green; the new tests take total above
- [ ] 6.3 Coverage floors not reduced; the recorder is well-covered or the scoring code is
- [ ] 6.4 AGENTS.md §9: add `server/…/access/` to "Where things live"
- [ ] 6.5 `docs/ROADMAP.md`: mark §3.1 shipped, inline the flush-interval default and the buffer
  cap so §2.1 can read them
