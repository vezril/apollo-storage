## Why

Apollo's gRPC contract (`object_api.proto`) lives inside `apollo-storage`, and every
client (Artemis, Hephaestus, Argus) carries its own view of it — the same drift risk the
async message contracts had. The **Lexicon** is being made the single source of truth for
every wire contract; its `refactor-grpc-into-lexicon` change moves Apollo's service `.proto`
into the Lexicon and publishes generated stubs (a Scala jar via GitHub Packages, a Python
package). This change is the **Apollo-side half of that migration**: the server stops
owning the `.proto` and instead generates its service from the pinned Lexicon artifact, so
server and clients can no longer disagree on the API shape.

## What Changes

- **Remove** `server/src/main/protobuf/apollostorage/grpc/object_api.proto` from this repo;
  the Apollo object-storage service definition now lives in the Lexicon.
- **Depend** on the pinned Lexicon Scala artifact (GitHub Packages) that contains the
  generated Apollo server stub (`ObjectApiPowerApi`), message types, and client; the
  `server` module implements the Lexicon-generated trait instead of a locally-generated one.
- **Reconfigure the build**: add the GitHub Packages resolver + credentials; stop running
  pekko-grpc codegen for the Apollo service (it now comes from the artifact). The standard
  `grpc.health.v1` health proto is **not** an Apollo contract and stays vendored locally.
- **Preserve the API surface exactly** — a move, not a redesign. The existing gRPC test
  suites (unary, streaming, listing, TLS+auth) pass unchanged against the Lexicon stubs,
  which is the migration's safety net.

## Capabilities

### New Capabilities
<!-- none — this is a provenance/build migration, not a new behaviour -->

### Modified Capabilities
- `object-api`: adds a requirement that the gRPC service contract is **sourced from the
  Lexicon** (single versioned source of truth, generated stubs, pinned version) rather than
  a repo-local `.proto`. The RPC methods, messages, and status mappings are unchanged.

## Impact

- **Code**: `build.sbt` (Lexicon dependency, GitHub Packages resolver/credentials, pekko-grpc
  codegen scope reduced to the vendored health proto only); deletion of the local Apollo
  `.proto`; imports in `ObjectApiImpl`/`GrpcServer`/tests repoint from the locally-generated
  `apollostorage.grpc.*` package to the Lexicon-published package (name TBD by the Lexicon).
- **Dependencies**: adds the Lexicon Scala artifact (pinned SemVer) from GitHub Packages;
  requires a `GITHUB_TOKEN` (read:packages) in local + CI builds.
- **Cross-repo sequencing (gating)**: this change is **blocked until the Lexicon implements
  and publishes** the gRPC stubs (its `refactor-grpc-into-lexicon` is currently explore-mode
  design capture). Apply order: Lexicon defines the proto → Lexicon publishes `vX.Y.Z` →
  this change pins and adopts it. The API surface is identical, so it is a coordinated move,
  not a break.
- **Out of scope**: changing the gRPC API surface; the client-side adoption in Artemis /
  Hephaestus / Argus (their own repos); the Lexicon's own codegen/publishing work; moving
  the standard health proto.
