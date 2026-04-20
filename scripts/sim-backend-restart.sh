#!/usr/bin/env bash
# =============================================================================
# Failure Scenario 2: Backend Restart Under Live Agent Load
#
# What this tests:
#   When the backend container is restarted, all three agent gRPC streams
#   are severed. Each agent's grpcSender enters exponential backoff and
#   reconnects once the backend is healthy again. No metrics should be lost
#   in the reconnection window — the agent buffers up to 100 metrics in
#   the channel (collector.go metricChan capacity).
#
# Expected behaviour:
#   1. All agents log:   "Stream to backend lost: ..."
#   2. Agents enter backoff (2s → 4s → 8s → ... → 30s max)
#   3. Backend restarts and passes health check (~30s JVM startup)
#   4. All agents log:   "Connected to backend at backend:50051"
#   5. Metrics resume; no gap larger than backoff + startup time in DB
#
# PostgreSQL note:
#   The database persists through the backend restart (separate container).
#   The history query after restart will show a gap corresponding to the
#   downtime — this is the expected, correct behaviour.
#
# Usage:
#   ./scripts/sim-backend-restart.sh
# =============================================================================
set -euo pipefail

echo "=== Failure Scenario 2: Backend Restart ==="
echo ""

for c in telemetry-agent-dev1 telemetry-agent-dev2 telemetry-agent-dev3; do
  if ! podman ps --format "{{.Names}}" | grep -q "^${c}$"; then
    echo "WARNING: ${c} is not running — restart scenario is partial"
  fi
done

echo "[$(date +%H:%M:%S)] Restarting telemetry-backend..."
podman restart telemetry-backend
echo "[$(date +%H:%M:%S)] Restart command issued. Waiting for health check..."
echo ""

TIMEOUT=60
ELAPSED=0
until podman inspect telemetry-backend \
        --format '{{.State.Health.Status}}' 2>/dev/null | grep -q "healthy"; do
  if [ "${ELAPSED}" -ge "${TIMEOUT}" ]; then
    echo "ERROR: backend did not become healthy within ${TIMEOUT}s"
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  echo -n "."
done
echo ""
echo "[$(date +%H:%M:%S)] Backend is healthy (took ${ELAPSED}s)"
echo ""
echo "Check that all three agents reconnected:"
echo "  podman-compose logs --tail=20 backend | grep 'Agent connected'"
echo ""
echo "Verify no data was permanently lost (expect a gap of ~${ELAPSED}s):"
echo "  podman exec telemetry-postgres psql -U telemetry -d telemetry \\"
echo "    -c \"SELECT device_id, COUNT(*) as samples,"
echo "               MAX(ts) as last_seen"
echo "        FROM metric_cpu"
echo "        GROUP BY device_id ORDER BY device_id;\""
