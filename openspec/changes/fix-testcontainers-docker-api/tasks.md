# Tasks — fix-testcontainers-docker-api

## 1. The bump

- [x] 1.1 `build.sbt`: `testcontainersVersion` `0.41.4` → `0.43.0`
- [x] 1.2 Confirm the resolved transitive versions actually move:
  `testcontainers-java` 1.19.1 → **1.20.2**, `docker-java` 3.3.3 → **3.4.0**
- [x] 1.3 Confirm no source change is required — `compile` and `Test/compile` clean

## 2. Verify

- [x] 2.1 Local: 215 tests green (core 32, server 183 non-container) — unchanged by the bump
- [x] 2.2 **CI: the bump alone was not enough.** First run on this branch reproduced the identical
  `client version 1.32 is too old` 400 with testcontainers 1.20.2 / docker-java 3.4.0 resolved, so
  the stale default does not live in the artifact that was bumped. Empirically falsified, recorded
  rather than retried
- [x] 2.3 Fallback applied: `DOCKER_API_VERSION: "1.43"` in the workflow-level `env` of both
  `ci.yml` and `release.yml` (release runs the full suite before publishing, so it needs the same
  pin). Pins what the client advertises instead of leaving it to a default nothing controls
- [ ] 2.4 CI: the 8 suites run instead of aborting. Still the only real assertion, still only
  answerable on a runner — watch `Compile & test`
- [ ] 2.5 AGENTS.md §8: record the pin as a known gap once CI confirms it works — it is a
  workaround for a client default, not a fix for it
