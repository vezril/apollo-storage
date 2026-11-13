# Change: add-static-analysis

> Tighten CI static analysis — coverage, CI-enforced scalafix, stricter Scala 3 warnings under
> `-Werror`, and security scanning (secrets + dependency + image CVEs) — on top of the existing
> scalafmt gate. Part of a constellation-wide effort (see `codex/docs/static-analysis.md`).

## Why

CI gates formatting and the test suite, and nothing else. Concretely, on `development`:

- **scalafix is configured but never run.** `sbt-scalafix` is in `project/plugins.sbt` and
  `.scalafix.conf` enables `DisableSyntax` + `OrganizeImports`, but no job invokes it, so the rules
  are available rather than enforced.
- **Warnings are not errors.** `-Wunused:all` and `-Wvalue-discard` are set, but **`-Werror` is
  not**, so every finding they produce is advisory and scrolls past in the log.
- **Coverage is unmeasured.** No scoverage, no report, no floor.
- **Nothing scans for committed secrets, vulnerable dependencies, or CVEs in the shipped image.**

This closes those gaps with native, serverless CI steps.

Not SonarQube: it's Java-first, and for Scala it merely dashboards Scapegoat/scoverage (the native
tools do the analysis) while being a RAM-heavy server. Scapegoat itself is skipped — its Scala 3
support is shaky; the Scala-3-native path is cranked `-W` flags + scalafix + scoverage.

## What Changes

- **static-analysis** (new): CI gains coverage reporting (scoverage), a CI-enforced `scalafixAll
  --check`, `-Werror` with an expanded scalac `-W` set, a secret scan (gitleaks), a
  dependency-update feed (Dependabot), and an image CVE scan (Trivy) on the published Docker image.

## Impact

- `project/plugins.sbt`: + `sbt-scoverage`. `build.sbt`: enable SemanticDB (for scalafix) +
  `-Werror` and additional `-W` flags, with each flag's findings **fixed rather than
  blanket-suppressed**.
- `.github/workflows/ci.yml`: a `scalafixAll --check` step, a coverage step (report; gate later via
  `coverageMinimumStmtTotal`), and a gitleaks job.
- `.github/workflows/release.yml`: a Trivy scan of the built image before push.
  `.github/dependabot.yml`: the github-actions ecosystem.
- No runtime/behavioral change; existing tests unaffected.
- **Out of scope:** SonarQube / a self-hosted dashboard (deferred), Scapegoat (Scala 3 support), and
  a coverage *floor* (reported first, gated once the number is known).

## Provenance

This change supersedes **PR #24** (`feature/static-analysis`, opened 2026-07-10). That branch carried
the same proposal and spec but went stale across v0.13.0, v0.13.1 and v0.14.0 and conflicts with
`development`; its mechanical lint fixes touched 23 source and test files that have since moved. The
proposal and spec are carried forward from it; the code-affecting half is redone against current
`development` rather than rebased. One claim from the original proposal is corrected here: it
described `-Werror` as already enforced — it is not, and this change adds it.
