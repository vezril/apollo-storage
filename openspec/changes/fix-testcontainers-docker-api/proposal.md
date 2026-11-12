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
- **Out of scope:** forcing the version with a `DOCKER_API_VERSION` env override. That hides an
  outdated client rather than replacing it, and is the fallback if the bump proves insufficient.
