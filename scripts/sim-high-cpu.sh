#!/usr/bin/env bash
# =============================================================================
# Failure Scenario 3: CPU Anomaly Injection
#
# What this tests:
#   Generates artificial CPU load inside a chosen agent container so that
#   the agent reports elevated cpu_percent values. After ANOMALY_INTERVAL_SECONDS
#   (30s in compose.yaml), the AI engine's Z-score analyser should detect
#   the spike against the rolling baseline and log an anomaly.
#
# How it works:
#   Runs a tight arithmetic loop inside the agent container for DURATION
#   seconds. The agent reads /proc/stat for this container's cgroup, so
#   the elevated usage appears in the metric stream sent to the backend.
#
# Expected behaviour:
#   1. Agent's cpu_percent spikes from ~1-5% to 80-100% for DURATION seconds
#   2. After the next scheduler cycle (≤30s), the AI engine logs:
#      "ANOMALY  device=<DEVICE_ID>  metric=cpu  z=...  risk=..."
#   3. Backend logs the same anomaly via AnomalyScheduler
#   4. If risk > 0.8, the ACTIONABLE log line appears (Phase 4 part 2 placeholder)
#
# Requirements:
#   - The agent container must have been running long enough to establish
#     a baseline (~10+ samples = ~50s at 5s interval). The Z-score analyser
#     requires MIN_SAMPLES (10) before it can detect anomalies.
#
# Usage:
#   ./scripts/sim-high-cpu.sh [device_id] [duration_seconds]
#   ./scripts/sim-high-cpu.sh dev1 60
# =============================================================================
set -euo pipefail

DEVICE_ID="${1:-dev1}"
DURATION="${2:-60}"
CONTAINER="telemetry-agent-${DEVICE_ID}"

echo "=== Failure Scenario 3: CPU Anomaly Injection ==="
echo "Target container : ${CONTAINER}"
echo "Load duration    : ${DURATION}s"
echo ""

if ! podman ps --format "{{.Names}}" | grep -q "^${CONTAINER}$"; then
  echo "ERROR: container '${CONTAINER}' is not running."
  exit 1
fi

echo "[$(date +%H:%M:%S)] Injecting CPU load into ${CONTAINER} for ${DURATION}s..."
echo "Watch for anomaly in AI engine logs:"
echo "  podman-compose logs -f ai-engine | grep -E 'ANOMALY|device=${DEVICE_ID}'"
echo ""

podman exec "${CONTAINER}" sh -c "
  END=\$(( \$(date +%s) + ${DURATION} ))
  i=0
  while [ \$(date +%s) -lt \$END ]; do
    i=\$(( i + 1 ))
  done
  echo 'CPU load complete (i='\${i}')'
"

echo ""
echo "[$(date +%H:%M:%S)] Load injection complete."
echo ""
echo "Verify the spike appeared in the database:"
echo "  podman exec telemetry-postgres psql -U telemetry -d telemetry \\"
echo "    -c \"SELECT ts, usage_percent"
echo "        FROM metric_cpu"
echo "        WHERE device_id='${DEVICE_ID}'"
echo "          AND ts > NOW() - INTERVAL '5 minutes'"
echo "        ORDER BY ts DESC LIMIT 20;\""
