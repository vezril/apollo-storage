#!/usr/bin/env bash
# Docker smoke test for the ApolloStorage image (service-runtime spec).
# Verifies: container reaches `healthy`, runs as a non-root user, and honors the
# HTTP_PORT environment override. Used by CI and runnable locally.
#
# Usage: scripts/docker-smoke-test.sh [image-tag]
#   image-tag defaults to apollostorage:<sbt-derived-version>
set -euo pipefail

IMAGE="${1:-}"
if [[ -z "$IMAGE" ]]; then
  VERSION="$(sbt -batch -error 'print server/version' | tail -n1 | tr -d '[:space:]')"
  IMAGE="apollostorage:${VERSION}"
fi
echo "==> Smoke testing image: $IMAGE"

C_DEFAULT="apollo-smoke-default"
C_OVERRIDE="apollo-smoke-override"

cleanup() {
  docker rm -f "$C_DEFAULT" "$C_OVERRIDE" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

wait_for_healthy() {
  local name="$1" tries=30
  while (( tries-- > 0 )); do
    local status
    status="$(docker inspect --format '{{.State.Health.Status}}' "$name" 2>/dev/null || echo missing)"
    case "$status" in
      healthy) return 0 ;;
      unhealthy) echo "FAIL: $name became unhealthy"; docker logs "$name"; return 1 ;;
      *) sleep 2 ;;
    esac
  done
  echo "FAIL: $name did not reach healthy in time"; docker logs "$name"; return 1
}

# --- 1. Default config: reaches healthy and serves /health on 8080. ---
echo "==> [1/3] default config -> healthy + GET /health = 200"
docker run -d --name "$C_DEFAULT" -p 18080:8080 "$IMAGE" >/dev/null
wait_for_healthy "$C_DEFAULT"
code="$(curl -s -o /tmp/apollo_health.json -w '%{http_code}' http://127.0.0.1:18080/health)"
[[ "$code" == "200" ]] || { echo "FAIL: host GET /health returned $code"; exit 1; }
grep -q '"status":"UP"' /tmp/apollo_health.json || { echo "FAIL: body not UP: $(cat /tmp/apollo_health.json)"; exit 1; }
echo "    OK: $(cat /tmp/apollo_health.json)"

# --- 2. Runs as a non-root user. ---
echo "==> [2/3] non-root user"
uid="$(docker exec "$C_DEFAULT" id -u | tr -d '[:space:]')"
[[ "$uid" != "0" ]] || { echo "FAIL: container runs as root (uid 0)"; exit 1; }
echo "    OK: uid=$uid (non-root)"

# --- 3. HTTP_PORT override honored. ---
echo "==> [3/3] HTTP_PORT=9090 override"
docker run -d --name "$C_OVERRIDE" -e HTTP_PORT=9090 -p 19090:9090 "$IMAGE" >/dev/null
wait_for_healthy "$C_OVERRIDE"
code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:19090/health)"
[[ "$code" == "200" ]] || { echo "FAIL: override GET /health on 9090 returned $code"; exit 1; }
echo "    OK: service listened on overridden port 9090"

echo "==> SMOKE TEST PASSED"
