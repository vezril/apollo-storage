# project-scaffolding

## Requirements

### Requirement: Semantic versioning via git tags

The project SHALL derive its version exclusively from git tags following `vMAJOR.MINOR.PATCH` (sbt-dynver); no version literal is committed to source.

#### Scenario: Version derived from a release tag
- **Given** the repository HEAD is tagged `v1.2.3`
- **When** the build computes the project version
- **Then** the version is `1.2.3`

#### Scenario: Untagged commit yields a distinguishable snapshot version
- **Given** HEAD is 2 commits after tag `v1.2.3`
- **When** the build computes the project version
- **Then** the version contains `1.2.3`, the commit distance/sha, and is marked as a snapshot (not publishable as a release)

#### Scenario: Edge case — malformed tag is ignored by release tooling
- **Given** a tag `release-1.2` (non-semver) is pushed
- **When** the release workflow's tag filter evaluates it
- **Then** no release build is triggered and no artifact is published

#### Scenario: Edge case — tag on a non-main commit does not release
- **Given** a tag `v9.9.9` is pushed on a commit that is not on `main`
- **When** the release workflow validates the tag's branch ancestry
- **Then** the workflow fails with an explicit error and publishes nothing

### Requirement: Two-branch strategy with protected main

The repository SHALL maintain `main` (latest stable release, protected, PR-only) and `development` (integration branch); feature branches SHALL target `development`, and `main` SHALL only receive merges from `development`.

#### Scenario: Feature flow
- **Given** a feature branch `feature/health-endpoint`
- **When** the work is complete
- **Then** it is merged into `development` via a pull request with passing checks

#### Scenario: Release flow
- **Given** `development` contains changes ready for release
- **When** a release PR from `development` to `main` is merged and `vX.Y.Z` is tagged
- **Then** `main` reflects the release and the release pipeline runs

#### Scenario: Edge case — direct push to main is rejected
- **Given** branch protection on `main`
- **When** a contributor attempts `git push origin main` directly
- **Then** the push is rejected by the remote

### Requirement: CI verification on every pull request

Every pull request targeting `development` or `main` SHALL run a GitHub Actions workflow that checks formatting (scalafmt), compiles all modules, and runs the full test suite; merging SHALL be blocked unless all checks pass.

#### Scenario: Green PR is mergeable
- **Given** a PR whose code is formatted, compiles, and passes all tests
- **When** the CI workflow completes
- **Then** all required checks are green and the PR is mergeable

#### Scenario: Edge case — failing test blocks merge
- **Given** a PR containing a failing ScalaTest suite
- **When** CI runs
- **Then** the test job fails, the required check is red, and GitHub blocks the merge

#### Scenario: Edge case — formatting violation blocks merge independently of tests
- **Given** a PR whose tests pass but contains scalafmt violations
- **When** CI runs
- **Then** the format check fails and the PR is not mergeable, and the failure message identifies the offending files
