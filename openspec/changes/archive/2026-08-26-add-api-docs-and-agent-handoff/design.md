## Context

Apollo serves gRPC and REST on separate ports; the HTTP listener already hosts `/health`, `/metrics`,
`/v1/buckets…`, and (when enabled) `/admin/blob-gc`, composed in `Main.scala` and wrapped by
`RequestTracing.withCorrelationId`. The OpenAPI document lives at `docs/rest-api.openapi.yaml`, is
linked from the README, and is enforced-to-exist by `scripts/verify-docs.sh` — so its path is fixed.

The deployment target is a homelab k3s cluster on a LAN, reached over Tailscale. There is no assumed
internet egress from a pod, and no CDN allowlist. Documentation that only renders when the browser can
reach a third-party host would be documentation that fails exactly when someone is debugging a broken
cluster.

## Goals / Non-Goals

**Goals:**

- A browsable, executable API reference served by Apollo itself, working with zero internet access.
- One source of truth for the OpenAPI document — the file already reviewed in the repo.
- A complete documented surface: no endpoint the service serves is absent from the document.
- An Insomnia collection that matches that surface, retargetable by environment variable.
- A handoff document that stays true because keeping it true is a stated requirement of every change.

**Non-Goals:**

- Generating the OpenAPI document from code (annotation-driven or via a spec-generation library). The
  hand-written document is small, reviewed, and readable; generation would add a dependency and a build
  step to solve a problem we do not have.
- Documenting the gRPC surface in OpenAPI. gRPC's contract is the protobuf in the-lexicon; the docs page
  links to it rather than paraphrasing it in a format that cannot express it.
- Auth on the documentation endpoints, or a write/"try it out"-mutating proxy of any kind.

## Decisions

### D1 — Swagger UI assets from a webjar, not a CDN or a vendored copy

Serve Swagger UI from `org.webjars:swagger-ui` on the classpath, pinned to an explicit version
(5.25.3), via pekko-http's `getFromResource` against `META-INF/resources/webjars/swagger-ui/<version>/`.

*Why:* it satisfies the offline requirement with one dependency line, and updates are a version bump
reviewed like any other.

*Alternatives:* **CDN** — rejected outright; breaks with no egress, and adds a third-party runtime
dependency to an internal tool. **Vendoring the dist into `src/main/resources`** — works offline but
commits hundreds of KB of minified third-party JS into the repo and makes updates a manual re-copy.
**A hand-rolled renderer** (e.g. a small HTML page that pretty-prints the YAML) — no dependency, but
loses the whole point: schema-aware browsing and try-it-out.

The webjar version appears in the asset path, which is brittle across upgrades. Rather than add
`webjars-locator` (another dependency, classpath scanning at runtime) the version is a single constant
in the code, and a test asserts the assets actually resolve from the classpath at that path — so a
version bump that breaks the path fails a test rather than producing a blank page in production.

### D2 — The OpenAPI document is packaged from `docs/`, not duplicated into `resources/`

An sbt `Compile / resourceGenerators` task copies `docs/rest-api.openapi.yaml` into the jar's managed
resources at build time; the route serves it from the classpath.

*Why:* the requirement is that the served contract cannot drift from the reviewed one. Copying at build
time makes drift structurally impossible, while keeping the file where the README, CI, and humans
already expect it.

*Alternatives:* **Move the file into `server/src/main/resources` and link there from the README** —
one location, but it relocates a documented path and buries a human-facing artifact inside the module's
resources. **Keep two copies in sync by discipline** — rejected; that is precisely the failure mode
this change exists to fix, and a test comparing the two would only detect the drift after it happened.
**Read from the filesystem at runtime** — rejected; `docs/` does not exist inside the container image.

### D3 — Docs endpoints are unauthenticated and safe-method-only

`/docs`, `/docs/openapi.yaml`, and the asset path answer `GET` without a token, mounted alongside
`healthRoutes` rather than inside the authenticated object routes.

*Why:* they expose the *shape* of the API — the same information as the repo, which is public — and
never data, configuration, or credentials. Requiring a token to read the docs would mean you need a
token to learn how to send a token. `/health` already sets this precedent.

*Trade-off accepted:* on a LAN-only service this discloses the API shape to anyone who can reach the
port. That is the same disclosure as the public GitHub repository, so it changes nothing material.

### D4 — Completeness is enforced by a test, not by review

A test asserts the documented `paths` cover the routes the service mounts. Without it, "the document is
complete" is a claim that decays on the next endpoint added — which is exactly what happened to the
document and the Insomnia collection this change is repairing.

The check compares against the known public HTTP surface (health, metrics, the object API, blob-gc). It
is deliberately a list the test owns rather than reflection over the pekko-http route tree: pekko routes
are opaque functions and cannot be enumerated reliably, so the honest mechanism is an explicit
inventory that a developer must consciously extend — and the test names that obligation.

### D5 — The handoff file lives at the repo root as `AGENTS.md`

*Why:* `AGENTS.md` is the emerging cross-tool convention and is read by more than one agent runtime,
where `CLAUDE.md` names a single vendor. The content is vendor-neutral (architecture, invariants,
workflow), so the neutral name is the honest one.

*Alternatives:* **`CLAUDE.md`** — read automatically by Claude Code, but implies the file is only for
one tool. Mitigation: the file is short enough to be read on request, and the README points to it.
**`docs/HANDOFF.md`** — tidier, but agents look at the root first, and discoverability is the entire
point of the artifact.

## Risks / Trade-offs

- **Webjar version drift breaks the asset path** → the version is one constant, and a classpath
  resolution test fails the build rather than shipping a blank docs page.
- **The completeness test becomes a rubber stamp** (someone adds a route and updates the inventory
  without documenting it) → the test asserts the inventory against the *document*, so extending the
  inventory without documenting the path fails. It cannot force someone to add the route to the
  inventory; that residual risk is accepted and named in `AGENTS.md`.
- **The handoff file rots despite the requirement** → its scenarios make staleness a spec violation, and
  `verify-docs.sh` checks that the paths it references still resolve. Prose accuracy still depends on
  the author; the file therefore records *where truth lives* (specs, README) rather than restating it.
- **Serving static assets from a service that streams large objects** → assets are small, cached by the
  browser, and served on the same listener that already serves `/metrics`; no new resource concern.
- **Swagger UI "try it out" issues real requests, including destructive ones** (`DELETE`) against
  whatever server is selected → this is standard behaviour and the same capability curl gives, but the
  docs page notes the selected server so it is never ambiguous which deployment a call would hit.

## Migration Plan

Additive and reversible. The new routes appear when the build ships; no data, schema, or config changes.
Rollback is redeploying the previous image. The only new runtime artifact is a classpath dependency, so
a failure to resolve it surfaces at build time, never at run time.

## Open Questions

- Should the docs page also link the gRPC contract (the-lexicon protobuf) by version? Leaning yes as a
  static link; pinning to the exact resolved lexicon version would be better but requires threading
  BuildInfo through, which can follow if it proves useful.
