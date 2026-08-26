# documentation

## Purpose

The artifacts that let a newcomer — human or AI — understand, run, and exercise this project
without reading the source: the README, the license, the runnable API collection, and the agent
handoff document. They are treated as part of the system, not commentary on it, so they are
verified rather than trusted.

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

### Requirement: Insomnia collection covers the HTTP API

The repository SHALL contain an importable Insomnia collection under `insomnia/` that exercises the
service's HTTP API, covering the object surface (bucket create/delete/list, object
upload/download/delete/list) alongside the operational endpoints (`/health`, `/metrics`,
`/admin/blob-gc`) and the gRPC object API. The collection SHALL drive the target host and bearer token
from environment variables rather than hard-coded values, so it works against localhost, the LAN, and
the tailnet without editing requests.

The collection SHALL be updated whenever an HTTP endpoint is added, removed, or changes shape, so it
never presents a strict subset of the API as though it were the whole.

#### Scenario: Collection imports and covers the object API

- **GIVEN** a fresh Insomnia install
- **WHEN** the collection file in `insomnia/` is imported
- **THEN** it imports without error, and contains a request for each documented `/v1/buckets…`
  operation as well as `/health`, `/metrics`, and `/admin/blob-gc`

#### Scenario: Retargeting requires only an environment change

- **GIVEN** the imported collection
- **WHEN** the base-URL and token environment values are changed to another deployment
- **THEN** every request targets the new deployment with no per-request edit

#### Scenario: Edge case — collection matches the documented surface

- **GIVEN** the collection and `docs/rest-api.openapi.yaml`
- **WHEN** the paths exercised by the collection are compared with the documented paths
- **THEN** every documented path is exercised by at least one request

### Requirement: Maintained agent-handoff document

The repository SHALL contain a handoff document at its root that lets a fresh AI coding session
(primarily Claude) become productive on this project without re-deriving its context from the source.
It SHALL cover, at minimum: what the service is and its architecture (event-sourced CQRS, bucket
entity, blob store behind an interface); the invariants a contributor must not break; the mandatory
workflow (TDD red-green-refactor, the OpenSpec change flow, branch and release conventions); the HTTP
and gRPC surface with pointers to the authoritative specs; the deployment picture; and a map of where
things live.

The document SHALL be updated as part of **every** subsequent change that alters architecture,
conventions, the API surface, or deployment — the update belongs in the same change as the feature, not
a later cleanup. It SHALL record what is true, including known gaps and deferred work, and SHALL NOT
duplicate content that the specs or README already own; it links to them instead.

#### Scenario: A fresh session can orient from the document alone

- **GIVEN** an AI session with no prior context on this repository
- **WHEN** it reads the handoff document
- **THEN** it can state what the service does, which invariants constrain a change, how work must be
  proposed and tested, and where the authoritative specs live — without reading the source tree first

#### Scenario: The document tracks the feature that changed the system

- **GIVEN** a change that adds an endpoint or alters a convention
- **WHEN** that change is implemented
- **THEN** the handoff document is updated within the same change, and its statements match the
  resulting code

#### Scenario: Edge case — referenced paths resolve

- **GIVEN** the handoff document
- **WHEN** each repository-relative path it references is checked
- **THEN** every referenced path exists

