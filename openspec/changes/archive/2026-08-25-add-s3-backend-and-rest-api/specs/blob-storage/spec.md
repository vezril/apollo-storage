# blob-storage

## MODIFIED Requirements

### Requirement: Configurable store root with environment override

The blob store backend SHALL be selectable via HOCON key `apollostorage.blob.backend`, overridable by
the `BLOB_BACKEND` environment variable, with values `filesystem` (the default) or `s3`; no backend is
hard-coded in source. For the **filesystem** backend, the store root SHALL be configured via
`apollostorage.blob.root`, overridable by the `BLOB_STORE_PATH` environment variable (default
`/var/lib/apollostorage/objects`). For the **s3** backend, the connection is configured per the
`s3-blob-store` capability. Regardless of backend, the object API behavior is unchanged.

#### Scenario: Environment override takes precedence
- **GIVEN** the HOCON default and a `BLOB_STORE_PATH` environment value
- **WHEN** the filesystem backend resolves configuration
- **THEN** the store uses the environment-specified root, not the default

#### Scenario: Backend selected by environment
- **GIVEN** `BLOB_BACKEND=s3`
- **WHEN** configuration is resolved
- **THEN** the service uses the S3 backend rather than the filesystem backend
