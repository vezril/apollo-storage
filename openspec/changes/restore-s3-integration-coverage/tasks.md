# Tasks — restore-s3-integration-coverage

## 1. Choose the replacement

- [x] 1.1 Confirm MinIO is genuinely unavailable: `minio/minio` 404s on Docker Hub,
  `quay.io/minio/minio` returns zero tags anonymously
- [x] 1.2 Weigh LocalStack vs `adobe/s3mock` vs authenticating the MinIO pull against what the suite
  proves — path-style, streaming multipart with checksum verification, ETag/md5 — and choose
  LocalStack for reimplementing S3 rather than stubbing it
- [x] 1.3 Pin a concrete tag (`2026.10.7`) rather than `:latest`

## 2. Restore the suite

- [x] 2.1 Swap the container: edge port 4566, `SERVICES=s3`, health wait on `/_localstack/health`
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
