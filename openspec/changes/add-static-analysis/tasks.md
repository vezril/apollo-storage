# Tasks — add-static-analysis

Tighten CI static analysis. The `-W`/scalafix parts are code-affecting (fix findings, don't
blanket-suppress); the scanners are CI/config only.

## 1. Coverage

- [x] 1.1 Add `sbt-scoverage` to `project/plugins.sbt`
- [x] 1.2 CI: run tests with coverage + `coverageReport` (reported, **not gated** — a floor via
  `coverageMinimumStmtTotal` is a later step); upload the report artifact

## 2. scalafix gate

- [x] 2.1 Enable SemanticDB (`semanticdbEnabled` + `semanticdbVersion`) so semantic rules run
- [x] 2.2 CI: `scalafixAll --check` as a gate (DisableSyntax + OrganizeImports); enabled
  `OrganizeImports.removeUnused`; legitimate interop/lifecycle `null`s marked `// scalafix:ok`

## 3. Stricter Scala 3 warnings

- [x] 3.1 `-Werror` on; `-Wnonunit-statement` added (production only — relaxed in Test, where
  ScalaTest `Assertion`/`.futureValue` discards are idiomatic); `-Wconf` silences generated
  pekko-grpc sources
- [x] 3.2 Resolve findings: production non-Unit discards (`val _ =`), unused imports/members
  (scalafix + removals), an unreachable match case — full suite still green

## 4. Secret scanning

- [x] 4.1 CI: gitleaks job scanning the repo + history (fails on a finding)

## 5. Dependency & image scanning

- [x] 5.1 `.github/dependabot.yml`: **github-actions** ecosystem (weekly). NOTE: Dependabot has no
  `sbt` ecosystem — sbt *version-updates* need **Scala Steward** (deferred follow-up); Dependabot
  *security alerts* still cover the sbt dependency graph
- [x] 5.2 Release workflow: **Trivy** scans the built image before push (fails on a fixable
  `CRITICAL`; `ignore-unfixed` so base-image noise doesn't wedge releases)

## 6. Verify

- [x] 6.1 Local: `-Werror` compile clean, `scalafixAll --check` + `scalafmtCheckAll` pass, full
  suite (server 122 / core 32) green, scoverage instruments + compiles under `-Werror`
