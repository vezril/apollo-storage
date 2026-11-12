# Tasks — fix-testcontainers-docker-api

## 1. Diagnosis

- [x] 1.1 Identify the failure: all 8 testcontainers suites abort with
  `client version 1.32 is too old. Minimum supported API version is 1.40`
- [x] 1.2 Establish it is not branch-specific — PR #39 (AGENTS.md prose only) fails identically,
  and the last green run was the v0.14.0 release on 2026-08-28. Runner-image drift, not a regression
- [x] 1.3 Note the blast radius: `release.yml` runs `sbt test` before publishing, so the release
  path is blocked too

## 2. Falsified attempts — kept on the record

- [x] 2.1 **Bump `testcontainersVersion` 0.41.4 → 0.43.0.** Not the fix: the identical 400
  reproduced with `testcontainers-java` 1.20.2 / `docker-java` 3.4.0 resolved. Kept anyway as a
  current baseline, not as the remedy
- [x] 2.2 **`DOCKER_API_VERSION=1.43` in the workflow env.** Not the fix: no effect in CI, and a
  probe proved the forked test JVM *did* inherit the variable — so not a delivery problem either.
  The env entries are removed again

## 3. Measure the mechanism

- [x] 3.1 Reproduce locally **without a Docker daemon**: point `DOCKER_HOST` at a Unix socket that
  logs the request line, and read off the version the client advertises
- [x] 3.2 Results — baseline `GET /v1.32/info`; with `DOCKER_API_VERSION=1.43`
  `GET /v1.32/info` (ignored); with `-Dapi.version=1.43` **`GET /v1.43/info`**. docker-java reads
  the `api.version` *system property*, not the environment variable

## 4. The fix

- [x] 4.1 `build.sbt`: `Test / javaOptions += "-Dapi.version=1.43"` — a `Test/javaOptions` entry
  because tests are forked, so an sbt-JVM flag would not reach them
- [x] 4.2 No workflow change needed: `release.yml` runs the same suite through the same build

## 5. Verify

- [x] 5.1 Local: `compile` + `Test/compile` clean, `scalafixAll --check` and `scalafmtCheckAll`
  pass, 215 tests green (core 32, server 183 non-container), `openspec validate --changes` and
  `scripts/verify-docs.sh` pass
- [x] 5.2 Local: the advertised API version is **measured** as 1.43, not assumed
- [x] 5.3 **CI confirmed the fix.** 7 of 8 suites now run and pass — 198 tests, up from 183. The
  handshake succeeds; the journal, projection, sharding and migration suites are exercising real
  Postgres again for the first time since 2026-08-28

## 6. The remaining suite — a separate breakage

- [x] 6.1 `S3BlobStoreIT` still aborted, on an unrelated cause: `pull access denied for
  minio/minio, repository does not exist`. `minio/minio` has been withdrawn from Docker Hub and
  `quay.io/minio/minio` requires authentication. Other Docker Hub pulls (Postgres, Ryuk) work, so
  this is specific to that image
- [x] 6.2 **Parked, by decision.** Ignored at class level — the abort happens in the
  `ForAllTestContainer` container-start lifecycle, before any test runs, so per-test ignores would
  not stop it. Verified locally: the suite reports `aborted 0` and executes no tests instead of
  failing the build
- [x] 6.3 The suite body and its reasoning are left intact, with a header explaining why the
  replacement is a judgement call rather than a swap: it covers path-style addressing, streaming
  multipart put with checksum verification, and ETag/md5 against a *real* S3 implementation, which
  is what QuObjects is in production. `adobe/s3mock` may diverge on those edges; `localstack` is
  closer but slower; authenticating the MinIO pull keeps the test identical
- [ ] 6.4 AGENTS.md §8: record both gaps — the `api.version` pin (a hard-coded client API version
  that will need revisiting when the Engine floor moves again) and **the S3 backend having no
  integration coverage** until a replacement image is chosen. Landing on #39, which already
  rewrites §8, to avoid a conflict with this branch
