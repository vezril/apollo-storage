# service-runtime — Spec Delta

## ADDED Requirements

### Requirement: Minimal Pekko application with health endpoint

The service SHALL run as a Pekko typed `ActorSystem` (no persistence in this capability) exposing `GET /health` over HTTP, returning `200 OK` with JSON `{"status":"UP","service":"apollostorage","version":"<version>"}` while the system is running.

#### Scenario: Health returns UP
- **Given** the service has started successfully
- **When** a client sends `GET /health`
- **Then** the response is `200` with `status = "UP"` and the build version in the body

#### Scenario: Edge case — unknown route
- **Given** the service is running
- **When** a client sends `GET /nope`
- **Then** the response is `404` and the connection remains healthy (subsequent `/health` still returns `200`)

#### Scenario: Edge case — health during shutdown
- **Given** the service has received a termination signal and coordinated shutdown has begun
- **When** a client sends `GET /health` before the port unbinds
- **Then** the response is `503` with `status = "DOWN"` (readiness is withdrawn before the actor system terminates)

### Requirement: Graceful startup and shutdown

The service SHALL bind its HTTP port before reporting ready, and on SIGTERM SHALL complete via Pekko Coordinated Shutdown: unbind, drain in-flight requests, then terminate the actor system, exiting with code 0.

#### Scenario: Clean SIGTERM
- **Given** the service is running in a container
- **When** the container runtime sends SIGTERM
- **Then** the process exits 0 within the shutdown timeout and no request in flight is abruptly reset

#### Scenario: Edge case — port already in use
- **Given** the configured port is occupied by another process
- **When** the service starts
- **Then** startup fails fast with a clear log message naming the port, and the process exits non-zero (no zombie actor system)

### Requirement: Docker image via sbt-native-packager

The build SHALL produce a Docker image (`sbt Docker/publishLocal`) that runs the service as a non-root user, exposes the HTTP port, and defines a container `HEALTHCHECK` against `/health`.

#### Scenario: Image boots and reports healthy
- **Given** the locally built image
- **When** the container is started with default configuration
- **Then** the container reaches `healthy` status and `GET /health` from the host returns `200`

#### Scenario: Edge case — runs as non-root
- **Given** the built image
- **When** `whoami`/UID is inspected inside the running container
- **Then** the process runs as a non-root user

#### Scenario: Edge case — configuration override via environment
- **Given** the image started with `HTTP_PORT=9090`
- **When** the container is running
- **Then** the service listens on 9090 (not the default) and the health check succeeds against it
