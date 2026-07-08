# release-publishing — Spec Delta

## ADDED Requirements

### Requirement: Release images from main tags

When a semver tag `vX.Y.Z` is pushed on `main`, GitHub Actions SHALL build the Docker image and publish it to Docker Hub tagged `X.Y.Z` and `latest`, only after the full test suite passes in the same workflow.

#### Scenario: Tagged release publishes
- **Given** tag `v0.1.0` pushed on `main` with valid Docker Hub credentials in repo secrets
- **When** the release workflow completes
- **Then** `docker pull <repo>/apollostorage:0.1.0` and `:latest` both succeed and point at the same digest

#### Scenario: Edge case — tests fail, nothing is published
- **Given** tag `v0.1.1` on a commit with a failing test
- **When** the release workflow runs
- **Then** the workflow fails at the test job and no image or tag is pushed to Docker Hub (no partial publish)

#### Scenario: Edge case — semver tags are immutable
- **Given** `0.1.0` already exists on Docker Hub
- **When** the release workflow for `v0.1.0` runs again
- **Then** the workflow refuses to overwrite the existing `0.1.0` tag and fails with an explicit message (`latest` is the only mutable tag)

### Requirement: Experimental images from development

Every push to `development` SHALL publish an image tagged `dev` and `dev-<short-sha>` after tests pass, so the homelab can trial unreleased builds deliberately.

#### Scenario: Development push publishes dev tags
- **Given** a commit pushed to `development` with passing tests
- **When** the workflow completes
- **Then** `:dev` and `:dev-<sha>` are available on Docker Hub

#### Scenario: Edge case — missing credentials fail fast
- **Given** `DOCKERHUB_TOKEN` is absent from repository secrets
- **When** the workflow reaches the publish step
- **Then** it fails before any push attempt with an error naming the missing secret

#### Scenario: Edge case — fork PRs never publish
- **Given** a pull request from a fork
- **When** CI runs for it
- **Then** only build/test jobs execute; publish jobs are skipped (secrets are not exposed to fork contexts)
