# documentation

Verification for this capability is a CI/manual checklist plus link/command execution checks, not ScalaTest.

## Requirements

### Requirement: Comprehensive README

The repository root SHALL contain a `README.md` including, at minimum: project description (GCS-inspired, event-sourced CQRS, homelab-production intent), CI/CD status badges, an **AI Usage Disclaimer** describing development by an AI SDLC team (Claude Code agents — product owner / developers / reviewers — with human review), a deployment example (docker compose with the service + PostgreSQL), a configuration example (HOCON + env-var overrides table), and instructions to run the application and the tests.

#### Scenario: Fresh-clone quickstart works
- **Given** a fresh clone on a machine with sbt and Docker
- **When** the README's "run the tests" and "run the application" commands are executed verbatim
- **Then** the tests pass and the service starts with `/health` returning `200`

#### Scenario: Edge case — badges resolve
- **Given** the rendered README on GitHub
- **When** each CI/CD badge URL is requested
- **Then** every badge returns an image reflecting an existing workflow (no 404 or "unknown" from a misnamed workflow file)

#### Scenario: Edge case — compose example is self-contained
- **Given** only the docker compose snippet copied from the README into an empty directory
- **When** `docker compose up` is run
- **Then** Postgres and ApolloStorage start, and the service reaches `healthy` without requiring undocumented steps

### Requirement: MIT license

The repository SHALL contain a `LICENSE` file with the MIT license text, current year, and copyright holder, and the README SHALL reference it.

#### Scenario: License present and referenced
- **Given** the repository root
- **When** `LICENSE` is inspected
- **Then** it is the MIT text with correct year/holder, and the README links to it

#### Scenario: Edge case — GitHub license detection
- **Given** the repository on GitHub
- **When** the repo metadata is viewed
- **Then** GitHub auto-detects the license as MIT (file is unmodified boilerplate apart from year/holder, so detection succeeds)

#### Scenario: Edge case — no conflicting license claims
- **Given** all source files and the build definition
- **When** scanned for license headers/metadata
- **Then** no file claims a different license than MIT (`licenses` setting in sbt matches)
