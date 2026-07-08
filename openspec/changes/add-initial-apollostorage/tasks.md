# Tasks — add-initial-apollostorage

TDD is non-negotiable: every implementation task is preceded by a failing-test task and followed by a refactor + run-tests task. Do not start an implementation task while its tests are green (that means the tests are wrong) or missing.

Before starting: consult `/Users/cference/Code/claude-toolkit` for relevant skills and agents.

## 1. Project Scaffolding & CI/CD (`project-scaffolding`)

- [x] 1.1 Initialize repo: sbt build with `core` + `server` modules (design D5), Scala 3.3 LTS, scalafmt + scalafix configs, `.gitignore`, sbt-dynver, sbt-native-packager
- [ ] 1.2 Create `development` branch; configure branch protection on `main` (PR-only, required checks)
- [ ] 1.3 **Tests first**: PR-verification workflow with a deliberately failing placeholder test → open a draft PR → confirm the check is red and merge is blocked (edge case: failing test blocks merge)
- [ ] 1.4 **Tests first**: introduce a scalafmt violation on a branch with green tests → confirm the format check fails independently (edge case)
- [ ] 1.5 Implement: finalize `ci.yml` (scalafmtCheck, compile, test) triggered on PRs to `development` and `main`
- [ ] 1.6 Implement release tag filter: workflow triggers only on `v[0-9]+.[0-9]+.[0-9]+`; add ancestry check that the tag is on `main`; verify malformed tag `release-1.2` triggers nothing (edge cases)
- [ ] 1.7 Verify sbt-dynver: tagged commit → clean version; untagged → snapshot version (scenario tests via CI step assertions)
- [ ] 1.8 Refactor workflows (shared setup via composite action / caching); re-run all checks

## 2. Service Runtime — Pekko + Docker + Health (`service-runtime`)

- [x] 2.1 **Red**: ScalaTest (Pekko HTTP route testkit) — `GET /health` returns 200 `UP` + version; `GET /nope` returns 404; health returns 503 once readiness withdrawn (edge cases)
- [x] 2.2 **Green**: implement typed `ActorSystem` guardian, health route, readiness flag wired to Coordinated Shutdown phases
- [x] 2.3 **Red**: startup test — occupied port ⇒ fast failure, non-zero exit, clear log (edge case)
- [x] 2.4 **Green**: implement bind-failure handling and exit semantics
- [x] 2.5 **Red**: Docker smoke test script/CI job — container reaches `healthy`; runs as non-root; `HTTP_PORT=9090` override honored (edge cases)
- [x] 2.6 **Green**: sbt-native-packager Docker config (non-root user, EXPOSE, HEALTHCHECK, env-driven config per design D8)
- [x] 2.7 **Refactor**: extract config loading; run full suite + smoke test

## 3. Domain Model (`domain-model`, pure `core` module)

- [x] 3.1 **Red**: `BucketName` property + example tests — valid names, uppercase rejection, length boundaries 2/3/63/64 (edge cases)
- [x] 3.2 **Green**: `BucketName` smart constructor returning `Either[DomainError, BucketName]`
- [x] 3.3 **Red**: `ObjectName` tests — nested keys OK; traversal inputs (`..`, leading `/`, backslash) rejected; 1025-byte UTF-8 rejected (edge cases)
- [x] 3.4 **Green**: `ObjectName` smart constructor (byte-length validation, segment checks)
- [x] 3.5 **Red**: value types — `Generation` monotonicity, `Checksums(crc32c, md5)`, `ObjectMetadata` equality/immutability
- [x] 3.6 **Green**: implement value types
- [x] 3.7 **Red**: command/event ADT tests — exhaustive handler coverage; events self-contained (state foldable from event list); commands accept validated types only (edge cases)
- [x] 3.8 **Green**: sealed command/event hierarchies
- [x] 3.9 **Red**: pure transition tests — commit increments generation; duplicate `CreateBucket` ⇒ `BucketAlreadyExists`, zero events; command after `BucketDeleted` ⇒ `BucketNotFound`, counters not resurrected (edge cases)
- [x] 3.10 **Green**: `(State, Command) => Either[DomainError, Seq[Event]]` and `(State, Event) => State`
- [x] 3.11 **Refactor**: simplify state representation, dedupe validation; run full `core` suite

## 4. Event Persistence (`event-persistence`, `server` module)

- [x] 4.1 **Red**: `EventSourcedBehaviorTestKit` suite — `CreateBucket` persists exactly one event + success reply; rejected command persists zero events; persistence ID frozen as `bucket|<name>` (edge cases)
- [x] 4.2 **Green**: `BucketEntity` `EventSourcedBehavior` delegating to pure domain transitions
- [x] 4.3 **Red**: serialization round-trip suite covering every event constructor (Jackson CBOR, design D4) (edge case)
- [x] 4.4 **Green**: serializer bindings + event schema conventions documented in code
- [x] 4.5 **Red**: testcontainers integration — events round-trip through real Postgres with correct seqNrs; env vars override HOCON; unreachable DB ⇒ health `DOWN` / bounded-retry exit (edge cases)
- [x] 4.6 **Green**: `pekko-persistence-r2dbc` wiring, HOCON config with env overrides, journal schema setup for tests
- [x] 4.7 **Red**: recovery suite — restart then commit yields generation 3; deleted bucket stays deleted after replay (edge case)
- [x] 4.8 **Green**: verify recovery behavior (should follow from 4.2; fix gaps)
- [x] 4.9 **Refactor**: extract persistence config module; run unit + integration suites

## 5. Publish to Docker Hub (`release-publishing`)

- [ ] 5.1 **Tests first**: workflow-level assertions — publish job skipped on fork PRs; missing `DOCKERHUB_TOKEN` fails before any push (edge cases; verify on a scratch branch)
- [ ] 5.2 Implement `release.yml`: on `v*` semver tag on `main` → test → build → push `X.Y.Z` + `latest`; add immutability guard (fail if `X.Y.Z` already exists on Docker Hub) (edge case)
- [ ] 5.3 Implement `development` publish: on push → test → push `dev` + `dev-<short-sha>`
- [ ] 5.4 Verify end-to-end: cut `v0.1.0`, pull both tags, confirm same digest; confirm failing-test commit publishes nothing (edge case)
- [ ] 5.5 Refactor: share build steps between ci/release/dev workflows

## 6. Documentation (`documentation`)

- [ ] 6.1 **Tests first**: doc-verification checklist in CI — quickstart commands executed verbatim on fresh clone; badge URLs resolve (edge case)
- [ ] 6.2 Write `README.md`: description, badges, AI Usage Disclaimer (SDLC agent team + human review), docker compose deployment example (service + Postgres), configuration table (HOCON keys ↔ env vars), run/test instructions
- [ ] 6.3 Verify compose example self-contained: copy snippet to empty dir, `docker compose up`, service reaches healthy (edge case)
- [ ] 6.4 Add MIT `LICENSE` (year/holder), sbt `licenses` setting, README link; confirm GitHub license auto-detection (edge cases)
- [ ] 6.5 Final pass: run every documented command once more on `development`, then open the release PR to `main`
