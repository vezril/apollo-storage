# Tasks — add-static-analysis

Tighten CI static analysis. The `-W`/scalafix parts are code-affecting (fix findings, don't
blanket-suppress); the scanners are CI/config only.

Each gate is **negative-tested** before it is trusted: introduce a violation, confirm the gate fails,
revert. A gate nobody has seen fail is not known to be a gate.

## 1. Coverage

- [x] 1.1 Add `sbt-scoverage` to `project/plugins.sbt`
- [x] 1.2 CI: run tests with coverage + `coverageReport` (reported, **not gated** — a floor via
  `coverageMinimumStmtTotal` is a later step); upload the report artifact

## 2. scalafix gate

- [x] 2.1 Enable SemanticDB (`semanticdbEnabled` + `semanticdbVersion`) so semantic rules run
- [x] 2.2 Enable `OrganizeImports.removeUnused`; CI runs `scalafixAll --check` as a gate
  (DisableSyntax + OrganizeImports). Of the 13 `null` findings, **8 were fixed** rather than
  suppressed (`Option(testKit).foreach(…)` / `Option(root).foreach(…)` in the ScalaTest lifecycle
  hooks); 5 are genuine interop and carry `// scalafix:ok DisableSyntax.null` with a reason:
  the JSSE default-trust-store `null` in `TlsContext`, the empty-`Mono` completion in
  `PersistenceMigration` + `ReadModelRepository`, `Path.getParent` at the filesystem root in
  `DocsPackagingSpec`, and ScalaTest's `should not be null` matcher in `BlobEnumerationSpec`
- [x] 2.3 Negative-test: a disallowed `null` fails `scalafixAll --check` — confirmed, exit 1 on
  `GateProbe.scala:5` (probe deleted)

## 3. Stricter Scala 3 warnings

- [x] 3.1 `-Werror` on; `-Wnonunit-statement` added (production only — relaxed in Test, where
  ScalaTest `Assertion`/`.futureValue` discards are idiomatic); `-Wconf` silences generated
  pekko-grpc sources
- [x] 3.2 Resolve findings — no blanket suppressions. 14 non-Unit discards bound with `val _ =`
  (cluster/management/binding lifecycle calls, `Promise.try*` races, a best-effort probe cleanup),
  12 unused imports and 2 unused `given ExecutionContext` members removed, and one unreachable
  match case fixed by ordering `GrpcServiceException` before its parent `StatusRuntimeException`
- [x] 3.3 Negative-test: a discarded non-Unit statement fails the compile — confirmed, `E176` on
  `GateProbe.scala:6` (probe deleted)

## 4. Secret scanning

- [x] 4.1 CI: gitleaks job scanning the repo + history (fails on a finding)

## 5. Dependency & image scanning

- [x] 5.1 `.github/dependabot.yml`: **github-actions** ecosystem (weekly). NOTE: Dependabot has no
  `sbt` ecosystem — sbt *version-updates* need **Scala Steward** (deferred follow-up); Dependabot
  *security alerts* still cover the sbt dependency graph
- [x] 5.2 Release workflow: **Trivy** scans the built image before push (fails on a fixable
  `CRITICAL`; `ignore-unfixed` so base-image noise doesn't wedge releases)

## 6. Verify

- [x] 6.1 Local: `-Werror` compile clean (main + test), `scalafixAll --check` + `scalafmtCheckAll`
  pass, scoverage instruments and compiles under `-Werror`. **215 tests green** (core 32, server
  183). ⚠️ The testcontainers ITs did **not** run — this machine has no container runtime — so CI
  is the first place the full suite executes. Flagged on the PR.
- [x] 6.2 `openspec validate --changes` passes
- [x] 6.3 AGENTS.md: dropped the "CI has no static-analysis gate" gap from §8, replaced with the two
  that remain (no coverage floor; Dependabot is actions-only), and added a "What CI gates" paragraph
  to §4 — including the `// scalafix:ok` trailing-prose trap
- [x] 6.4 Close PR #24 referencing this change — closed with the reason recorded on it
