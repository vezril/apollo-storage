# Tasks — adopt-lexicon-grpc-contracts

**Gating:** this change is the consumer half of the Lexicon's `refactor-grpc-into-lexicon`.
Do **not** start section 2 until the Lexicon has published a Scala artifact containing the
Apollo server stub (power APIs) with the `apollostorage.grpc` package preserved (design
D45/D46). Section 1 confirms that precondition.

## 1. Upstream precondition

- [x] 1.1 Confirm the Lexicon has published a Scala artifact (GitHub Packages) with the Apollo
  `ObjectApi` server stub generated using `server_power_apis` (so the trait carries
  `Metadata`) and the `apollostorage.grpc` package preserved; record its exact coordinates
  and pinned version (design D45/D46/D49)

## 2. Build wiring

- [x] 2.1 Add the GitHub Packages resolver + `GITHUB_TOKEN` credentials to `build.sbt`
  (mirroring HermesMQ) and the pinned Lexicon dependency (design D48)
- [x] 2.2 Add `GITHUB_TOKEN` (read:packages) as a CI secret and document the local PAT in the
  README build section

## 3. Remove the local contract

- [x] 3.1 Delete `server/src/main/protobuf/apollostorage/grpc/object_api.proto`
- [x] 3.2 Scope the `server` pekko-grpc codegen to the vendored health proto only
  (`grpc/health/v1/health.proto`), so no Apollo-service codegen runs locally (design D47)

## 4. Verify the move (behaviour-identical)

- [x] 4.1 Compile the `server` module against the Lexicon artifact; fix only import/build
  wiring (D45 means `apollostorage.grpc.*` imports should not need to change)
- [x] 4.2 **Verify**: run the full unit + integration suites — `ObjectApiSpec`,
  `ObjectApiListingIT`, `GrpcServerSpec`, `GrpcMetricsSpec`, and `TlsAuthSpec` pass unchanged,
  proving the API surface is identical (design D49); run `scalafmtCheckAll`
- [x] 4.3 **Verify**: build the Docker image and run the deployment smoke (a `grpcurl` call +
  health) to confirm the running server serves the same surface from the Lexicon stubs

## 5. Docs

- [x] 5.1 README: note that the gRPC contract is defined in the Lexicon (link), that the build
  pins a Lexicon version, and the `GITHUB_TOKEN` requirement; remove references to a local
  Apollo `.proto`
