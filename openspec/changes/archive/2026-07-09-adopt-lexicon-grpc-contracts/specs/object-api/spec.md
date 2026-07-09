# object-api — Spec Delta

Records that the gRPC service contract is sourced from the Lexicon (design D45–D49). The
RPC methods, message shapes, and status mappings are unchanged — only the definition's home
and build provenance move.

## ADDED Requirements

### Requirement: gRPC contract sourced from the Lexicon

The Apollo object-storage gRPC service definition SHALL be sourced from the shared Lexicon
artifact — a single, versioned source of truth — rather than a repo-local `.proto`. The
server SHALL generate its service stubs from a **pinned** Lexicon version, preserving the
`apollostorage.grpc` protobuf package so the API surface (RPC methods, messages, and status
mappings) is identical to the previous local definition. A version mismatch between the
server and the pinned contract SHALL surface as a build error, not a runtime failure.

#### Scenario: Server builds against the pinned Lexicon contract
- **GIVEN** the Apollo gRPC service published in the Lexicon at a pinned version
- **WHEN** the server module builds
- **THEN** it generates and implements the service from that artifact, with no repo-local
  Apollo `.proto` present

#### Scenario: The API surface is unchanged by the move
- **GIVEN** the existing gRPC test suites (unary, streaming, listing, TLS + auth)
- **WHEN** they run against the Lexicon-generated stubs
- **THEN** they pass unchanged — the RPC methods and message shapes are identical, proving the
  move is not a redesign

#### Scenario: Edge case — an incompatible contract fails the build
- **GIVEN** the server pinned to a Lexicon version incompatible with its implementation
- **WHEN** the module is compiled
- **THEN** the incompatibility is a build/type error, not a runtime surprise
