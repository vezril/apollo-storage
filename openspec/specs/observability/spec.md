# observability

A Prometheus metrics endpoint and the application/JVM metrics ApolloStorage exposes for
scrape-based monitoring (design D40–D44). Metrics are additive and behaviourally
transparent; the object API and `/health` are unchanged. On by default and disableable via
`apollostorage.metrics.enabled`.

## Requirements

### Requirement: Prometheus metrics endpoint

The service SHALL expose `GET /metrics` on the HTTP port in Prometheus text exposition
format (`text/plain; version=0.0.4`) when metrics are enabled. The endpoint SHALL be
reachable without authentication even when API authentication is enabled, and SHALL
expose no bucket or object names, tokens, or payload data.

#### Scenario: Scrape returns exposition-format metrics
- **GIVEN** metrics enabled
- **WHEN** a client sends `GET /metrics`
- **THEN** the response is `200` with content type `text/plain; version=0.0.4` and a body
  in Prometheus exposition format

#### Scenario: Metrics need no credential
- **GIVEN** metrics enabled and API authentication enabled
- **WHEN** `GET /metrics` is called with no `authorization` header
- **THEN** it returns the metrics normally

#### Scenario: Edge case — metrics disabled
- **GIVEN** metrics disabled via configuration
- **WHEN** `GET /metrics` is called
- **THEN** it returns `404` and no metrics collectors are installed

### Requirement: gRPC request metrics

The service SHALL record, for every gRPC API call, a request counter labelled by RPC
method and outcome status and a latency histogram labelled by RPC method, captured at the
transport boundary so that all unary and streaming RPCs are covered. Label values SHALL be
drawn only from closed sets (method names, status codes) and MUST NOT include bucket or
object names.

#### Scenario: Successful RPC is counted and timed
- **GIVEN** metrics enabled
- **WHEN** a `CreateBucket` RPC completes successfully
- **THEN** the request counter for that method with an OK outcome increments and the
  latency histogram for that method records an observation

#### Scenario: Failed RPC records its status
- **WHEN** an RPC fails (e.g. `NOT_FOUND` or `UNAUTHENTICATED`)
- **THEN** the request counter increments with a label reflecting that non-OK status

### Requirement: Blob store metrics

The service SHALL record, for each blob-store operation (put, get, delete): an operation
counter labelled by operation and outcome, a latency histogram labelled by operation, and
a bytes counter labelled by direction (read/written).

#### Scenario: A put records latency, outcome, and bytes written
- **GIVEN** metrics enabled
- **WHEN** an object payload is written to the blob store
- **THEN** the put latency histogram records an observation, the put operation counter
  increments with a success outcome, and the written-bytes counter increases by the
  payload size

#### Scenario: A failed operation is counted as a failure
- **WHEN** a blob-store operation raises an error
- **THEN** its operation counter increments with a failure outcome

### Requirement: Runtime and build metrics

The service SHALL export standard JVM metrics (heap, garbage collection, threads, file
descriptors), a build-info gauge carrying the service version as a label, and a readiness
gauge that is `1` when the service is ready to serve and `0` otherwise.

#### Scenario: JVM, build-info, and readiness are exposed
- **GIVEN** metrics enabled and the service is ready
- **WHEN** `/metrics` is scraped
- **THEN** the output includes JVM process metrics, a build-info gauge labelled with the
  version, and a readiness gauge with value `1`
