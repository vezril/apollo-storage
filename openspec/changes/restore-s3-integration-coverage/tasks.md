# Tasks — restore-s3-integration-coverage

## 1. Choose the replacement

- [x] 1.1 Confirm MinIO is genuinely unavailable: `minio/minio` 404s on Docker Hub,
  `quay.io/minio/minio` returns zero tags anonymously
- [x] 1.2 Weigh LocalStack vs `adobe/s3mock` vs authenticating the MinIO pull against what the suite
  proves — path-style, streaming multipart with checksum verification, ETag/md5 — and choose
  LocalStack for reimplementing S3 rather than stubbing it
- [x] 1.3 Pin a concrete tag rather than `:latest`
- [x] 1.4 **First pin (`2026.10.7`) was wrong — caught by CI, then reproduced locally.** LocalStack's
  2026.x images refuse to start without a paid `LOCALSTACK_AUTH_TOKEN`: "License activation failed",
  exit code 55. The container never booted, so this was never a semantics problem
- [x] 1.5 Repinned to **`4.0.3`**, which predates the licence gate. Verified locally under finch:
  boots to `Ready.` with zero licence errors, health returns 200, `s3: available`, ListBuckets
  answers. Documented in the suite that this pin is a dead branch — a 2024 build of a now-licensed
  product — with `adobe/s3mock` named as the fallback

## 2. Restore the suite

- [x] 2.1 Swap the container: edge port 4566, `SERVICES=s3`, health wait on `/_localstack/health`
  (endpoint confirmed by hand against 4.0.3, not assumed from docs)
- [x] 2.2 Remove the class-level `@Ignore`
- [x] 2.3 Factor the duplicated `S3Config` into one `s3Config(bucket)` helper
- [x] 2.4 Raise `patienceConfig` 30s → 60s for the slower boot
- [x] 2.5 Leave all seven tests unchanged — the point is that they still pass
- [x] 2.6 Fix the scaladoc summary line, which still said "against a MinIO testcontainer"

## 3. Verify

- [x] 3.1 `Test/compile`, `scalafixAll --check`, `scalafmtCheckAll` clean
- [ ] 3.2 **CI: all seven assertions pass against LocalStack.** Not checkable locally — no container
  runtime on this machine. This is the whole question of the change, so CI is the verdict
- [ ] 3.3 Record the new server coverage number, then set `coverageMinimumStmtTotal` from it
- [ ] 3.4 AGENTS.md §8: drop the "S3 blob backend has no integration coverage" gap
