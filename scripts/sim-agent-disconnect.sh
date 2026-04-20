#!/usr/bin/env bash
# =============================================================================
# Failure Scenario 1: Agent Disconnect and Reconnect
#
# What this tests:
#   The agent's gRPC reconnection loop (collector.go grpcSender) uses
#   exponential backoff from 2s to 30s. When an agent container is killed
#   and restarted, the backend should log "Agent disconnected" then
#   "Agent connected" once the backoff resolves.
#
# Expected behaviour:
#   1. Backend logs:  "Agent disconnected: device_id=dev2 total_metrics=N"
#   2. Container stops for PAUSE_SECONDS
#   3. Container restarts (podman-compose restart)
#   4. Agent logs:    "Connected to backend at backend:50051"
#   5. Backend logs:  "Agent connected: device_id=dev2"
#   6. Metrics resume in PostgreSQL within one sampling interval (5s)
#
# Verification:
#   Watch backend logs in a separate terminal:
#     podman-compose logs -f backend
#
# Usage:
#   ./scripts/sim-agent-disconnect.sh [device_id] [pause_seconds]
#   ./scripts/sim-agent-disconnect.sh dev2 30
# =============================================================================
set -euo pipefail

DEVICE_ID="${1:-dev2}"
PAUSE="${2:-30}"
CONTAINER="telemetry-agent-${DEVICE_ID}"

echo "=== Failure Scenario 1: Agent Disconnect ==="
echo "Target container : ${CONTAINER}"
echo "Pause duration   : ${PAUSE}s"
echo ""

if ! podman ps --format "{{.Names}}" | grep -q "^${CONTAINER}$"; then
  echo "ERROR: container '${CONTAINER}' is not running."
  echo "Start the full stack first:  podman-compose up -d"
  exit 1
fi

echo "[$(date +%H:%M:%S)] Stopping ${CONTAINER}..."
podman stop "${CONTAINER}"
echo "[$(date +%H:%M:%S)] Container stopped. Waiting ${PAUSE}s to simulate downtime..."
sleep "${PAUSE}"

echo "[$(date +%H:%M:%S)] Restarting ${CONTAINER}..."
podman start "${CONTAINER}"
echo "[$(date +%H:%M:%S)] Container started."
echo ""
echo "Watch for reconnection in backend logs:"
echo "  podman-compose logs -f backend | grep 'device_id=${DEVICE_ID}'"
echo ""
echo "Verify metrics resumed in PostgreSQL:"
echo "  podman exec telemetry-postgres psql -U telemetry -d telemetry \\"
echo "    -c \"SELECT ts, usage_percent FROM metric_cpu"
echo "        WHERE device_id='${DEVICE_ID}' ORDER BY ts DESC LIMIT 3;\""
