# static-analysis Specification

## Purpose

Define what the build refuses to accept, so that code quality and supply-chain hygiene are enforced
mechanically rather than remembered. Apollo runs unattended and holds data other services depend on,
so a defect that reaches `main` is expensive to find later — this capability moves that detection to
the pull request.

It covers the gates that run on every change (formatting is a prerequisite; beyond it, lint rules via
scalafix, compiler warnings escalated to errors, and measured test coverage) and the scanning that
guards what ships (committed secrets, dependency advisories, and vulnerabilities in the published
image).

Two principles the requirements below assume. A gate is only trusted once it has been **seen to
fail**, so each code-affecting rule is negative-tested when introduced. And a gate's findings are
**fixed rather than suppressed** — a blanket exclusion converts an enforced rule back into a comment,
which is worse than not having the rule, because it looks like coverage.

## Requirements

### Requirement: Test coverage is measured and gated in CI
CI SHALL produce a statement-coverage report for the build via scoverage, and SHALL enforce a
per-module statement floor (`coverageMinimumStmtTotal`) so that a drop in coverage fails the build.

Each floor SHALL be set from a **measured** figure rather than an aspirational one, and SHALL sit
below every measurement the same module can legitimately produce — a module's coverage varies with
how the suite is invoked, since another module's tests may exercise its code. A floor that fires
depending on which command a developer typed teaches people to route around it.

Floors SHALL be raised as coverage improves; a floor that never moves is a comment, not a gate.

#### Scenario: Coverage is reported on a build
- **WHEN** CI runs the test job
- **THEN** a statement-coverage report is produced and uploaded as an artifact

#### Scenario: Coverage below the floor fails the build
- **GIVEN** a module with a configured statement floor
- **WHEN** its measured statement coverage falls below that floor
- **THEN** the build fails rather than reporting the drop and passing

### Requirement: scalafix is enforced in CI
CI SHALL run `scalafixAll --check` so the configured rules (DisableSyntax, OrganizeImports) act as a
gate rather than being merely available; a violation SHALL fail the build. SemanticDB SHALL be enabled
so semantic rules can run.

#### Scenario: A scalafix violation fails the build
- **WHEN** code violates a configured scalafix rule (e.g. a disallowed `null`)
- **THEN** the CI scalafix check fails

### Requirement: Stricter Scala 3 compiler warnings
The build SHALL enable an expanded scalac `-W` set beyond `-Wunused:all` (e.g. `-Wvalue-discard`,
`-Wnonunit-statement`) under `-Werror`, added incrementally so each new flag's findings are resolved
rather than blanket-suppressed.

#### Scenario: A newly-flagged warning is an error
- **GIVEN** an enabled `-W` flag under `-Werror`
- **WHEN** code triggers that warning
- **THEN** compilation fails

### Requirement: Secret scanning
CI SHALL scan the repository (and its history) for committed secrets with gitleaks and SHALL fail the
build on a finding.

#### Scenario: A committed secret fails the build
- **WHEN** a credential is present in the repository or its history
- **THEN** the secret-scan job fails

### Requirement: Dependency and image vulnerability scanning
The project SHALL receive automated dependency updates for its **GitHub Actions** via Dependabot,
and the release pipeline SHALL scan the built Docker image for known vulnerabilities with Trivy.
(Dependabot has no `sbt` ecosystem, so Scala/sbt *version-update* PRs are a Scala Steward follow-up;
Dependabot *security alerts* still cover the sbt dependency graph.)

#### Scenario: The image is scanned on release
- **WHEN** the release workflow builds the service image
- **THEN** the image is scanned for known CVEs and the release fails on a fixable CRITICAL

#### Scenario: Actions are kept up to date
- **WHEN** a workflow action has a newer version
- **THEN** Dependabot opens an update PR for it
