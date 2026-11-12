# Tasks — fix-testcontainers-docker-api

## 1. The bump

- [x] 1.1 `build.sbt`: `testcontainersVersion` `0.41.4` → `0.43.0`
- [x] 1.2 Confirm the resolved transitive versions actually move:
  `testcontainers-java` 1.19.1 → **1.20.2**, `docker-java` 3.3.3 → **3.4.0**
- [x] 1.3 Confirm no source change is required — `compile` and `Test/compile` clean

## 2. Verify

- [x] 2.1 Local: 215 tests green (core 32, server 183 non-container) — unchanged by the bump
- [ ] 2.2 **CI: the 8 integration suites run instead of aborting.** This is the real assertion and
  it can only be made on a runner. Watch `Compile & test` on the PR; a green job means the Engine
  handshake succeeded and the journal/projection/sharding/S3 suites actually executed
- [ ] 2.3 If the handshake still fails, fall back to a `DOCKER_API_VERSION` env override in
  `ci.yml` + `release.yml` and record it in AGENTS.md §8 as a known gap
