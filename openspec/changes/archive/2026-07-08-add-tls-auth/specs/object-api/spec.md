# object-api — Spec Delta

Records that the object API is subject to authentication (design D35). The existing
RPC behaviors and status mappings are unchanged; the health service stays open.

## ADDED Requirements

### Requirement: Authenticated access to object operations

The bucket and object RPCs SHALL require a valid credential when authentication is
enabled, failing unauthenticated callers with `UNAUTHENTICATED` before any bucket or
object is touched. This covers `CreateBucket`, `DeleteBucket`, `PutObject`, `GetObject`,
`HeadObject`, `DeleteObject`, `ListBuckets`, and `ListObjects`; the
`grpc.health.v1.Health` service remains reachable without a credential.

#### Scenario: Unauthenticated object call is rejected before side effects
- **GIVEN** authentication enabled
- **WHEN** `PutObject` is streamed without a valid bearer token
- **THEN** the RPC fails with `UNAUTHENTICATED` and no object is committed

#### Scenario: Health is exempt from authentication
- **GIVEN** authentication enabled
- **WHEN** the health service is queried without a token
- **THEN** it responds normally
