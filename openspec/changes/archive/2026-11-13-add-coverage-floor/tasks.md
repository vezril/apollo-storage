# Tasks — add-coverage-floor

## 1. Get a trustworthy number first

- [x] 1.1 Deliberately sequence this **after** restoring `S3BlobStoreIT` — server coverage moved
  66.49% → 72.61% when those seven tests came back, so measuring first would have pinned the floor
  six points low
- [x] 1.2 Read the post-restoration figures from CI: core 96.88%, server 72.61% statement

## 2. Set the floors

- [x] 2.1 `server`: `coverageMinimumStmtTotal := 70` + `coverageFailOnMinimum := true`
- [x] 2.2 `core`: initially 95 against the CI figure — **wrong**, and caught by running it: core
  measures 92.97% from `core/test` alone because server tests also cover core classes. Set to 90,
  below both measurements, so the gate cannot fire based on which command someone typed
- [x] 2.3 Statement only — server branch coverage is 56.74%, and gating correlated metrics doubles
  the false-failure surface for little signal

## 3. Verify

- [x] 3.1 The gate genuinely fails: the 95 floor produced
  `Coverage is below minimum [92.97% < 95.00%]` and a non-zero exit
- [x] 3.2 The chosen floor passes in isolation: `core/test core/coverageReport` → exit 0 at 92.97%
- [x] 3.3 CI: the whole-build run passes both floors — core **96.88% ≥ 90**, server
  **72.61% ≥ 70**, verified in the run log rather than assumed
