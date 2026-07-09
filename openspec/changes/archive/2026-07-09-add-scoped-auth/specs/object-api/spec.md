# object-api — Spec Delta

The object RPCs now require the scope appropriate to their operation, not merely any valid
credential (design D60).

## MODIFIED Requirements

### Requirement: Authenticated access to object operations

The bucket and object RPCs SHALL require, when authentication is enabled, a valid credential
**whose scope covers the operation** — failing a missing/unknown credential with `UNAUTHENTICATED`
and an insufficiently-scoped one with `PERMISSION_DENIED`, before any bucket or object is touched.
The **read** operations (`GetObject`, `HeadObject`, `ListBuckets`, `ListObjects`) require `read`
scope; the **write** operations (`CreateBucket`, `DeleteBucket`, `PutObject`, `DeleteObject`)
require `write` scope. The `grpc.health.v1.Health` service remains reachable without a credential.

#### Scenario: Unauthenticated object call is rejected before side effects
- **GIVEN** authentication enabled
- **WHEN** `PutObject` is streamed without a valid bearer token
- **THEN** the RPC fails with `UNAUTHENTICATED` and no object is committed

#### Scenario: A read-scoped token is refused a write RPC
- **GIVEN** authentication enabled and a `read`-scoped token
- **WHEN** it calls `DeleteObject`
- **THEN** the RPC fails with `PERMISSION_DENIED` and nothing is deleted

#### Scenario: A read-scoped token may call read RPCs
- **GIVEN** a `read`-scoped token
- **WHEN** it calls `GetObject` or `ListObjects`
- **THEN** the RPC is authorized

#### Scenario: Health is exempt from authentication
- **GIVEN** authentication enabled
- **WHEN** the health service is queried without a token
- **THEN** it responds normally
