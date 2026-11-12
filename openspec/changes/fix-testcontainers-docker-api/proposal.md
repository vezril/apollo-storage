# Change: fix-testcontainers-docker-api

> Raise `testcontainersVersion` from `0.41.4` to `0.43.0` so the Docker client can talk to the
> GitHub runner's Engine again. Every integration suite is currently aborting in CI.

## Why

All 8 testcontainers suites abort before their first test, on every branch:

```
UnixSocketClientProviderStrategy: failed with exception BadRequestException
(Status 400: {"message":"client version 1.32 is too old.
 Minimum supported API version is 1.40, please upgrade your client to a newer version"})
→ java.lang.IllegalStateException: Could not find a valid Docker environment.
```

`testcontainers-scala` **0.41.4** resolves `testcontainers-java` **1.19.1** → `docker-java`
**3.3.3**, which negotiates Engine API **1.32**. GitHub's `ubuntu-latest` image has since moved to a
Docker Engine that refuses anything below **1.40**, so the client cannot connect at all.

Nothing in the repository changed to cause this — the runner image did. It went unnoticed because no
pull request was opened between the v0.14.0 release (2026-08-28, when the suites last ran green) and
2026-11-12.

This matters beyond a red check: `release.yml` runs `sbt test` before publishing, so **the release
path is blocked too**, and the integration tests have silently not been exercising the journal,
projections, sharding or the S3 backend for that whole window.

## What Changes

- `build.sbt`: `testcontainersVersion` `0.41.4` → `0.43.0` (the latest release, and the only one
  built on `testcontainers-java` 1.20.x — `docker-java` 3.4.0).
- No source or test changes: the `testcontainers-scala` API used here is unchanged between the two
  versions.

## Impact

- No production code, no runtime behaviour, no API surface. Test infrastructure only.
- **Verification is CI.** The failure only reproduces against a Docker daemon that requires API
  ≥1.40, and it cannot be confirmed fixed on a machine with no container runtime. Locally the bump
  resolves cleanly, compiles, and leaves the 215 non-container tests green; whether the Engine
  handshake now succeeds is answered by the `Compile & test` job on the PR, not by a local run.
## What actually fixes it — measured, not inferred

Two attempts were falsified in CI before the mechanism was understood:

1. **Bumping testcontainers was not enough.** The identical 400 reproduced with
   `testcontainers-java` 1.20.2 / `docker-java` 3.4.0 resolved, so the stale default does not live
   in the artifact that was bumped.
2. **`DOCKER_API_VERSION` is not read.** Setting it in the workflow env had no effect — and the
   forked test JVM demonstrably *did* inherit it, so this was not a delivery problem.

The mechanism was then measured locally **without a Docker daemon at all**, by pointing
`DOCKER_HOST` at a Unix socket that logs the request line it receives:

| Setting | Request the client makes |
|---|---|
| baseline | `GET /v1.32/info` |
| `DOCKER_API_VERSION=1.43` (env) | `GET /v1.32/info` — ignored |
| `-Dapi.version=1.43` (system property) | **`GET /v1.43/info`** |

docker-java reads its `api.version` **system property**, not the environment variable. Because tests
are forked, it has to be a `Test / javaOptions` entry rather than a flag on the sbt JVM.

## Impact

- `build.sbt`: `Test / javaOptions += "-Dapi.version=1.43"`. This also covers `release.yml`, which
  runs the same suite through the same build before publishing — no workflow change is needed, and
  the ineffective `DOCKER_API_VERSION` env entries added during the second attempt are removed.
- `build.sbt`: `testcontainersVersion` 0.41.4 → 0.43.0 is **kept but was not the fix** — a current
  client is a better baseline to carry the pin on, and it resolves, compiles, and leaves the 215
  non-container tests green.
- No production code, no runtime behaviour, no API surface. Test infrastructure only.
- **The pin is a workaround for a client default, not a fix for it.** Recorded as such, and bound
  for AGENTS.md §8.
- **Still unverified locally:** that the suites now *pass* against a real Engine. The advertised
  version is measured; the handshake and the suites themselves need a daemon this machine does not
  have (Finch is nerdctl/containerd with no Docker API, so it cannot host testcontainers). CI
  answers that.
