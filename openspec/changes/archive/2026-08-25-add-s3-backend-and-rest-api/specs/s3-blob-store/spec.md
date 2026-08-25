# s3-blob-store

## ADDED Requirements

### Requirement: S3-backed streaming, checksum-verified persistence
The S3 backend SHALL persist an object payload by streaming it to an S3-compatible store, computing
crc32c and md5 in a single pass, and SHALL store the payload under the same opaque `BlobRef` key the
filesystem backend would assign. If expected checksums are supplied and differ from the computed
values, the put SHALL fail and no retrievable object SHALL remain.

#### Scenario: Checksum mismatch leaves nothing committed
- **WHEN** a payload is streamed to the S3 backend and the computed checksums differ from the supplied expected checksums
- **THEN** the put fails and no object is retrievable at that reference

### Requirement: Atomic visibility via multipart completion
An object stored through the S3 backend SHALL become retrievable only once its upload is completed; a
failed or aborted upload SHALL leave no retrievable object (the multipart upload realizes the
crash-safe staging that the filesystem backend achieves with temp-file-then-rename).

#### Scenario: An aborted upload is invisible
- **WHEN** an upload to the S3 backend is aborted before completion
- **THEN** no object is retrievable at that reference, and the incomplete upload is reclaimable as debris

### Requirement: Streaming read-back from S3
The S3 backend SHALL stream a stored payload back by reference, or report absence when no object
exists at that reference, without buffering the whole payload in memory.

#### Scenario: Read back a stored payload
- **WHEN** a previously stored reference is requested
- **THEN** its bytes are streamed back; a reference with no object yields an empty/absent result

### Requirement: Enumeration and reconciliation via S3 listing
The S3 backend SHALL enumerate stored payloads via object listing (returning each reference with its
size and last-modified time) and enumerate incomplete/aborted uploads via multipart-upload listing, so
that the existing blob-gc orphan-and-debris reconciliation operates against S3 without changing its
requirements.

#### Scenario: Reconciliation sees stored objects and upload debris
- **WHEN** reconciliation enumerates the S3 backend
- **THEN** it lists stored payloads (with size and last-modified) and any incomplete uploads, and can reclaim an aged incomplete upload

### Requirement: S3 connection configuration and secret credentials
The S3 backend SHALL be configured by environment (endpoint, region, bucket, and path-style
addressing) and SHALL obtain its access-key/secret credentials from injected secrets — never from
source or the image. Path-style addressing SHALL be supported for S3-compatible stores such as
QuObjects.

#### Scenario: Credentials come from secrets, not source
- **WHEN** the S3 backend initializes
- **THEN** its endpoint/bucket come from configuration and its credentials from injected secrets, with no credential baked into the image

### Requirement: Startup readiness against the target bucket
At startup the service using the S3 backend SHALL verify the target bucket is reachable and exists; an
unreachable or missing bucket SHALL surface as unhealthy or a fast non-zero exit, so a misconfigured
store fails visibly rather than dropping writes at commit time.

#### Scenario: Unreachable bucket fails fast
- **GIVEN** an S3 endpoint/bucket that is unreachable or missing
- **WHEN** the readiness check runs
- **THEN** it fails with a clear error and the service reports `DOWN` or exits non-zero
