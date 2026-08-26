# documentation

Verification for this capability is a CI/manual checklist plus link/command execution checks, not
ScalaTest.

## ADDED Requirements

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
