# object-api — Spec Delta

Adds listing to the existing gRPC object API, served from the read model
(design D23). Existing object-api requirements are unchanged.

## ADDED Requirements

### Requirement: List buckets

The API SHALL expose `ListBuckets` returning the known bucket names from the read
model, ordered by name, with keyset pagination (`page_size` and a `page_token`
that is the last name of the previous page; the response returns the next
`page_token`, empty when the listing is exhausted).

#### Scenario: Buckets are listed in order
- **GIVEN** buckets `alpha`, `beta`, and `gamma` exist and have propagated to the read model
- **WHEN** `ListBuckets` is called
- **THEN** it returns `alpha`, `beta`, `gamma` in that order

#### Scenario: Edge case — pagination via page_token
- **GIVEN** more buckets than one page holds
- **WHEN** `ListBuckets` is called with a `page_size` smaller than the total
- **THEN** the first response returns that many names and a non-empty `page_token`,
  and calling again with that token returns the next page

### Requirement: List objects by prefix

The API SHALL expose `ListObjects(bucket, prefix, page_size, page_token)` returning
the objects of a bucket whose keys start with `prefix`, ordered by key, with keyset
pagination. Each entry carries the object's key, generation, size, content type,
and checksums. Deleted objects SHALL NOT appear. A missing bucket SHALL fail with
`NOT_FOUND`.

#### Scenario: Objects are listed by prefix
- **GIVEN** a bucket with keys `photos/a.jpg`, `photos/b.jpg`, and `docs/x.txt` in the read model
- **WHEN** `ListObjects` is called with prefix `photos/`
- **THEN** it returns `photos/a.jpg` and `photos/b.jpg`, ordered, and not `docs/x.txt`

#### Scenario: Edge case — deleted objects are absent
- **GIVEN** an object that was listed and is then deleted
- **WHEN** `ListObjects` is called after the deletion propagates
- **THEN** the deleted key is no longer returned

#### Scenario: Edge case — listing a missing bucket
- **WHEN** `ListObjects` is called for a bucket that does not exist
- **THEN** the RPC fails with status `NOT_FOUND`
