# request-tracing

## ADDED Requirements

### Requirement: Per-request correlation ID
The service SHALL mint a unique correlation ID for every inbound request on both the gRPC and HTTP
surfaces, and SHALL place it in the logging MDC so — via the structured-logging field promotion — it
appears as a field on every log line emitted while handling that request. Each request SHALL receive
its own ID; IDs SHALL NOT be reused across requests.

#### Scenario: Each request gets a distinct ID on its logs
- **WHEN** two separate requests are handled
- **THEN** each request's log lines carry a `correlationId` field, and the two IDs differ

### Requirement: The correlation ID is returned to the caller
The service SHALL return the correlation ID to the caller on every response so it can be quoted when
troubleshooting: as the HTTP response header `X-Correlation-Id`, as the gRPC response metadata entry
`x-correlation-id`, and — on a gRPC error — additionally embedded in the status trailer message. The
ID SHALL be returned on failures as well as successes.

#### Scenario: A successful response carries the ID
- **WHEN** a request completes successfully
- **THEN** the response carries the correlation ID (HTTP header / gRPC metadata) matching the ID on that request's log lines

#### Scenario: A failed gRPC call surfaces the ID in its error
- **WHEN** a gRPC request fails with an error status
- **THEN** the correlation ID is present both in the response metadata and within the error trailer message

### Requirement: The correlation ID propagates across async boundaries
Log statements emitted while handling a request SHALL carry that request's correlation ID even when
they run on a different thread than the request boundary (for example inside `Future` continuations or
after an actor ask). Enabling TRACE for the service's own code SHALL therefore yield correlated
trace-level lines for the operation, not orphaned lines with no ID.

#### Scenario: A trace line deep in the call chain is correlated
- **GIVEN** the service's own logger is at TRACE
- **WHEN** a request triggers a trace-level log inside the object/blob service code running on a pooled thread
- **THEN** that line carries the same `correlationId` as the request's boundary log lines

### Requirement: Per-request access logging
The service SHALL emit a per-request access log at INFO on both surfaces: one line when a request is
received and one when it completes, the completion line including the method, the resulting status,
and the elapsed duration. These lines SHALL carry the correlation ID like all other request-scoped
logs.

#### Scenario: A request produces entry and completion lines
- **WHEN** a request is handled
- **THEN** an INFO line records its receipt and a second INFO line records its method, status, and duration, both bearing the correlation ID

### Requirement: Configurable log verbosity
The service SHALL let an operator set the log verbosity by environment variable without a rebuild:
the root level and a dedicated level for the service's own (`apollostorage`) code SHALL be
independently configurable, defaulting to INFO, and SHALL support raising the service's own code to
TRACE while leaving third-party libraries at a quieter level.

#### Scenario: Apollo code goes to TRACE without third-party noise
- **WHEN** the operator sets the service-code log level to TRACE and leaves the root at INFO
- **THEN** the service's own trace lines are emitted while third-party libraries remain at INFO

### Requirement: Inbound correlation IDs are not trusted
The service SHALL always generate its own correlation ID and SHALL NOT trust, adopt, or echo a
client-supplied correlation value from the request. A caller therefore cannot inject a chosen value
into the service's logs or response via this mechanism.

#### Scenario: A client-supplied ID is ignored
- **WHEN** a request arrives carrying a client-set correlation header
- **THEN** the service ignores that value, generates its own ID, and returns the generated ID
