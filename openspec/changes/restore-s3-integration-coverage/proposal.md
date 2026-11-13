# Change: restore-s3-integration-coverage

> Bring `S3BlobStoreIT` back by moving it from the withdrawn MinIO image to a pinned LocalStack,
> closing the one real regression from the CI-hardening work.

## Why

`S3BlobStoreIT` was ignored at class level because `minio/minio` was withdrawn from Docker Hub and
`quay.io/minio/minio` requires authentication. That left the **S3 blob backend with no integration
coverage at all** — and S3 is the production backend (QuObjects on the NAS), so the untested surface
was the one that actually runs.

It was parked rather than swapped on purpose: the replacement decides what the suite still proves.

## What Changes

- `S3BlobStoreIT`: container becomes `localstack/localstack:2026.10.7` on the single edge port 4566
  with `SERVICES=s3`; the `@Ignore` is removed.
- The seven tests are **untouched**. Only the container, endpoint and credentials change.
- Config construction is factored into one `s3Config(bucket)` helper — it was duplicated in full
  between `afterStart` and the readiness test, and the swap would have meant editing both.

## Why LocalStack over the alternatives

| Option | Verdict |
|---|---|
| **LocalStack** (chosen) | Reimplements S3 semantics rather than stubbing them — multipart, ETag/md5 and path-style behave like the real thing. Slower to boot. |
| `adobe/s3mock` | Fast, but a mock. It may diverge precisely on multipart and checksum edges, which is what this suite exists to check. |
| MinIO with registry auth | Keeps the test byte-identical, but needs credentials in CI secrets and ties the suite to a vendor's commercial gate. |

These tests cover path-style addressing, streaming multipart put **with checksum verification**, and
ETag/md5 — the edges where a mock stops being evidence. QuObjects is a real S3 implementation, so the
double should be too.

## Impact

- Test-only. No production code, no API, no behaviour change.
- `patienceConfig` raised from 30s to 60s: LocalStack boots slower than MinIO did, and the 6 MiB
  multipart put is not instant.
- The tag is **pinned**. `:latest` makes a suite silently time-dependent, and this one has already
  been broken once by an upstream image decision.
- Server coverage will rise once these seven tests execute again, which matters for choosing the
  `coverageMinimumStmtTotal` floor — that follow-up should read the number *after* this lands, not
  before.

## Verification

- `Test/compile`, `scalafixAll --check` and `scalafmtCheckAll` clean.
- **Not verifiable locally:** this machine has no container runtime, so the suite cannot be run
  here. Whether LocalStack satisfies the same seven assertions MinIO did is answered by CI, and by
  nothing else. If LocalStack diverges on a checksum or multipart edge, that shows up there.
