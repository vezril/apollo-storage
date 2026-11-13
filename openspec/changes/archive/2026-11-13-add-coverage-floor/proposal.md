# Change: add-coverage-floor

> Turn the reported coverage number into a gate, now that there is a trustworthy number to set it
> from.

## Why

`add-static-analysis` shipped scoverage deliberately **reported, not gated**, with the reasoning
written into its spec: a floor "MAY be enforced once the suite is mature; until then coverage is
reported, not gated, so an immature suite does not block the build." The point of reporting first
was to choose the floor from evidence instead of guessing.

That evidence now exists, and it was worth waiting for:

| | While `S3BlobStoreIT` was parked | After it was restored |
|---|---|---|
| Server statement coverage | 66.49% | **72.61%** |
| Tests | 230 | 237 |

Setting the floor before restoring that suite would have pinned server **six points low** — a gate
that permanently enshrines a regression is worse than no gate.

## What Changes

- `core`: `coverageMinimumStmtTotal := 90`, `coverageFailOnMinimum := true`
- `server`: `coverageMinimumStmtTotal := 70`, `coverageFailOnMinimum := true`

## Why those numbers

**Server: 70** against a measured 72.61%. Close enough to mean something, with room for the
integration suites' run-to-run variation.

**Core: 90** against a measured 96.88% — and this one is subtler than it looks. Core measures
**96.88%** under CI's whole-build `coverage test coverageReport`, but only **92.97%** from
`core/test` alone, because server tests also exercise core classes. The number depends on how it is
invoked. A floor of 95 looked correct against the CI figure and **broke `core/test`** on the first
local run, so it is set below both. A gate that fires on the command a developer happens to type is
a gate people learn to bypass.

**Statement only, not branch.** Server branch coverage is 56.74%; gating two correlated metrics
roughly doubles the false-failure surface for little extra signal.

## Impact

- CI only. No production code, no runtime behaviour.
- The gate is verified to actually fail: an initial 95 floor on core produced
  `Coverage is below minimum [92.97% < 95.00%]` and a non-zero exit. It was found by running it, not
  by trusting the setting.
- These floors should **rise as coverage does**. A floor that never moves is just a comment.
- Note the floors cannot be satisfied locally on a machine with no container runtime — but the test
  suite itself cannot run there either, so this takes nothing away.
