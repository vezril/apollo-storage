## Context

Apollo's gRPC surface is defined by `server/src/main/protobuf/apollostorage/grpc/object_api.proto`
(package `apollostorage.grpc`), compiled inline by the `server` module's pekko-grpc plugin
into the server power API (`ObjectApiPowerApi`, with request `Metadata` for auth, D35),
message types, and a client. A standard `grpc/health/v1/health.proto` is vendored alongside.

The Lexicon (`the-lexicon`) is becoming the constellation's single source of truth for every
wire contract. Its `refactor-grpc-into-lexicon` change (explore-mode design capture) moves
Apollo's service `.proto` into the Lexicon and extends the Lexicon codegen to publish gRPC
stubs — a **Scala jar via GitHub Packages** (Apollo server + Artemis/Hephaestus clients) and
a **Python package** (Argus client), under one SemVer + protobuf-evolution policy. This
change is the Apollo-side consumer half of that migration.

## Goals / Non-Goals

**Goals:**
- Apollo generates its gRPC service from the **pinned Lexicon artifact**, not a local `.proto`.
- The move is **source-compatible and behaviour-identical** — existing tests are the proof.
- The build cleanly consumes a GitHub Packages artifact with pinned versioning.

**Non-Goals:**
- Any change to the gRPC API surface (methods, messages, status mappings) — a move, not a
  redesign.
- The Lexicon's own proto/codegen/publishing work (upstream, its repo).
- Client adoption in Artemis / Hephaestus / Argus (their repos).
- Moving the standard health proto out of Apollo.

## Decisions

### D45 — Preserve the protobuf package `apollostorage.grpc`
The Lexicon SHALL keep the `package apollostorage.grpc;` declaration when it hosts the proto,
so the generated Scala classes keep identical fully-qualified names (`apollostorage.grpc.*`).
Consequence: Apollo's `import apollostorage.grpc.*` sites in `ObjectApiImpl`, `GrpcServer`,
and the test suites are **unchanged** — the move is invisible to Apollo's source. Alternative
(re-package under a Lexicon namespace) was rejected: it would force churn across the server
and every test for zero benefit and would make the "move, not a change" guarantee harder to
see in the diff.

### D46 — Consume the generated stub artifact, not the raw proto
Apollo depends on the Lexicon **Scala jar** (which carries the generated `ObjectApiPowerApi`
trait, message types, client, and service descriptors) and runs **no codegen for the Apollo
service**; it simply implements the trait from the jar. Rationale: avoids fragile cross-repo
proto-source wiring in sbt, and the jar already contains everything the server and its tests
import. Alternative (pull the `.proto` from the Lexicon jar and re-run pekko-grpc locally) was
rejected: more build machinery, and it re-introduces a local codegen step the whole point was
to remove.

### D47 — The health proto stays vendored in Apollo
`grpc/health/v1/health.proto` is a **standard gRPC contract, not an Apollo API**, so it does
not belong in the constellation's IDL. Apollo keeps a minimal pekko-grpc codegen scoped to the
health proto only (the Apollo service proto is deleted). Alternative (Lexicon vendors health
too) was rejected: health is not a shared *domain* contract, and every gRPC server needs it
regardless of the Lexicon.

### D48 — GitHub Packages resolver + pinned SemVer
`build.sbt` adds the GitHub Packages resolver and `GITHUB_TOKEN` credentials (read:packages),
mirroring how HermesMQ already consumes a published Scala artifact, and pins an **exact**
Lexicon version. A producer/consumer version mismatch is then a build/type error, never a
runtime surprise. CI gets `GITHUB_TOKEN`; local builds use a personal token.

### D49 — The existing gRPC suites are the migration's spec
Because the API surface is unchanged, no new runtime tests are added. `ObjectApiSpec`,
`ObjectApiListingIT`, `GrpcServerSpec`, `GrpcMetricsSpec`, and `TlsAuthSpec` — all of which
import `apollostorage.grpc.*` and exercise unary, streaming, listing, reflection, metrics, and
TLS+auth — passing **unchanged** against the Lexicon-generated stubs is the acceptance
criterion. The `server_power_apis` codegen option must be enabled in the Lexicon so the
generated trait still carries `Metadata` (auth, D35); a plain-API artifact would fail to
compile against `ObjectApiImpl`, catching the mismatch at build time.

## Risks / Trade-offs

- **Upstream not ready (the gating risk)** → the Lexicon change is explore-mode; nothing is
  published yet. Mitigation: this proposal is authored now but its **apply is gated** on the
  Lexicon publishing `vX.Y.Z`; do not start implementation until the artifact resolves.
- **Power-API option drift** → if the Lexicon generates plain (non-power) APIs, `ObjectApiImpl`
  won't compile (no `Metadata`). Mitigation: require `server_power_apis` in the Lexicon; D49's
  build is the check.
- **Package rename by the Lexicon** → would force Apollo import churn. Mitigation: D45 fixes the
  package as a cross-repo contract.
- **GitHub Packages auth in CI/local** → a missing token breaks resolution. Mitigation: add the
  CI secret and document the local PAT in the README build section.
- **Reflection descriptors** → `GrpcServer` registers `ServerReflection.partial(List(ObjectApi,
  Health))`; the `ObjectApi` descriptor must come from the Lexicon jar. Low risk (it ships in
  the artifact), but verified by the reflection-using paths in the suites.

## Migration Plan

1. **(Upstream)** Lexicon publishes `vX.Y.Z` with the Apollo server stub (power APIs), messages,
   and client, package `apollostorage.grpc` preserved.
2. Add the GitHub Packages resolver + credentials and the pinned Lexicon dependency to
   `build.sbt`; delete `object_api.proto`; scope pekko-grpc codegen to the health proto only.
3. Compile + run the full suite — green existing gRPC tests prove the surface is identical.
4. Release Apollo (routine version bump); no runtime, wire, or behaviour change ships.

**Rollback**: revert the commit — the local `.proto` and its codegen return from git history;
because the wire surface is identical either way, there is no data or client impact.

## Open Questions

- Exact Lexicon artifact coordinates (groupId / artifact name) — defined by the Lexicon build.
- Whether the Lexicon jar also exports the reflection service descriptors Apollo registers
  (assumed yes; confirm when the artifact exists).
- Whether Apollo continues to consume the **client** stub from the same jar for its tests
  (assumed yes — the jar includes it, and the tests import `ObjectApiClient` unchanged).
