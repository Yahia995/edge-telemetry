<div align="center">

# AI Engine — Anomaly Detection Service

[![Python](https://img.shields.io/badge/Python-3.12-3776AB?style=flat-square&logo=python&logoColor=white)](https://python.org)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.111-009688?style=flat-square)](https://fastapi.tiangolo.com)
[![Algorithm](https://img.shields.io/badge/Algorithm-Z--Score-ff9?style=flat-square)](.)
[![Phase](https://img.shields.io/badge/Phase-4%20Complete-success?style=flat-square)](.)

Statistical anomaly detection for edge device metric streams.

</div>

---

## 📋 Table of Contents

- [Overview](#overview)
- [Algorithm](#algorithm)
- [API Reference](#api-reference)
- [Running](#running)
- [Configuration](#configuration)
- [Request / Response Format](#request--response-format)
- [Integration with the Backend](#integration-with-the-backend)
- [Design Decisions](#design-decisions)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)

---

## Overview

The AI engine is a stateless FastAPI service that receives recent metric history for one device and returns an anomaly assessment for each metric stream. It is called by the Ktor backend's `AnomalyScheduler` every 60 seconds.

### Responsibilities

- Receive a batch of time-series samples (cpu, memory, network) for one device
- Run Z-score analysis on each stream independently
- Return per-metric results and an overall risk score
- Log detected anomalies clearly for operational visibility

### What it deliberately does not do

- Store state — every call is independent; the backend owns the history
- Pull data from the database — the backend pushes samples to the engine
- Make control decisions — it returns risk scores; the backend acts on them

This design keeps the service horizontally scalable and trivially testable: given the same input, the output is always identical.

---

## Algorithm

### Z-Score Statistical Detection

For a stream of N samples, the engine splits them into a **baseline** (all but the last) and the **current** observation (the last sample):

```
baseline = samples[:-1]
current  = samples[-1]

mean = mean(baseline)
std  = population_std(baseline)

z = |current - mean| / std
```

A z-score ≥ `ANOMALY_THRESHOLD` (default 3.0) is classified as an anomaly. This means the current value is more than 3 standard deviations from the recent baseline — an event that should occur by chance roughly 0.3% of the time under a normal distribution.

### Risk Score

The risk score normalises z to `[0.0, 1.0]`:

```
risk_score = min(z / (threshold × 2), 1.0)
```

| Z-score | Risk score | Interpretation |
|---------|------------|----------------|
| 0.0 | 0.00 | Baseline-normal |
| 1.5 | 0.25 | Mild deviation |
| 3.0 (threshold) | 0.50 | Anomaly boundary |
| 4.8 | 0.80 | Actionable (backend alert) |
| 6.0+ | 1.00 | Severe anomaly |

`overall_risk` in the response is `max(risk_score)` across all three metric streams — a single value the backend can threshold on.

### Guard Conditions

| Condition | Reason | Response |
|-----------|--------|----------|
| `len(samples) < 11` | Cannot establish a meaningful baseline | `reason: "insufficient_data"`, `risk_score: 0.0` |
| `std < 1e-6` | Flat signal (e.g. swap always 0) | `reason: "no_variance"`, `risk_score: 0.0` — prevents division by zero and infinite z-scores |

### Population vs Sample Standard Deviation

`statistics.pstdev()` (population) is used rather than `statistics.stdev()` (sample). The baseline window *is* the population of interest — we are not estimating the standard deviation of a larger unseen population, we are characterising the signal that was actually observed. Using sample std (Bessel's correction) would inflate `std` and reduce `z`, producing conservative results that miss real anomalies.

### Limitations (for the academic write-up)

- **Assumes approximate Gaussian distribution.** CPU and memory usage under normal steady-state load are roughly so. Network traffic is more heavy-tailed — a higher threshold (4.0) reduces false positives for bursty interfaces.
- **Does not detect gradual drift.** A slow memory leak that stays within 3σ is invisible to Z-score. CUSUM or PELT change-point detection would catch it.
- **No cross-metric correlation.** CPU and memory spikes are analysed independently. A real workload failure might produce correlated spikes that a multivariate model (e.g. Isolation Forest) would detect at lower z-scores.
- **Why Z-score for Phase 4:** no training data is required; the sliding window the backend already maintains is sufficient; the output is fully auditable (mean, std, z exposed in the response). The `ZScoreAnalyzer` is the only class that changes when upgrading to a more sophisticated algorithm.

---

## API Reference

### `POST /analyze`

Analyse recent metric streams for one device.

**Request:**
```json
{
  "device_id": "dev1",
  "cpu_samples": [
    { "timestamp": 1769030254083, "value": 1.63 },
    { "timestamp": 1769030259084, "value": 2.91 }
  ],
  "memory_samples": [
    { "timestamp": 1769030254083, "value": 20.34 },
    { "timestamp": 1769030259084, "value": 20.15 }
  ],
  "network_samples": [
    { "timestamp": 1769030254083, "value": 0.0016 },
    { "timestamp": 1769030259084, "value": 0.0015 }
  ]
}
```

**Response:**
```json
{
  "device_id": "dev1",
  "analyzed_at": 1769030320000,
  "results": {
    "cpu": {
      "is_anomaly": false,
      "z_score": 0.4821,
      "risk_score": 0.0804,
      "mean": 2.1,
      "std": 0.85,
      "current_value": 2.91,
      "sample_count": 720,
      "reason": "ok"
    },
    "memory": {
      "is_anomaly": false,
      "z_score": 0.1203,
      "risk_score": 0.0200,
      "mean": 20.28,
      "std": 0.31,
      "current_value": 20.15,
      "sample_count": 720,
      "reason": "ok"
    },
    "network": {
      "is_anomaly": false,
      "z_score": 0.0812,
      "risk_score": 0.0135,
      "mean": 0.0014,
      "std": 0.0003,
      "current_value": 0.0015,
      "sample_count": 720,
      "reason": "ok"
    }
  },
  "overall_risk": 0.0804
}
```

**Anomaly example** (cpu spike):
```json
{
  "cpu": {
    "is_anomaly": true,
    "z_score": 5.34,
    "risk_score": 0.89,
    "mean": 2.1,
    "std": 0.85,
    "current_value": 94.7,
    "sample_count": 720,
    "reason": "anomaly"
  },
  "overall_risk": 0.89
}
```

**Error responses:**

| Status | Condition |
|--------|-----------|
| `422 Unprocessable Entity` | Analysis raised an unexpected exception; body contains `detail` string |

---

### `GET /health`

Liveness probe.

**Response:**
```json
{
  "status": "healthy",
  "version": "0.1.0",
  "threshold": 3.0,
  "timestamp": 1769030264084
}
```

Returns 200 as long as the process is running. Used by the backend's `AiEngineClient.isHealthy()` before each scheduler cycle, and by the Podman `HEALTHCHECK`.

---

## Running

### Local development (uses existing `mlenv`)

```bash
mlenv   # source ~/envs/ml/bin/activate

cd ai-engine
pip install -r requirements.txt

python main.py
# INFO  Uvicorn running on http://0.0.0.0:8000
```

### Standalone test
```bash
# Health check
http GET localhost:8000/health

# Manual analysis with 15 samples (minimum is 11)
http POST localhost:8000/analyze \
  device_id=test-dev \
  cpu_samples:='[
    {"timestamp":1,"value":2.1},{"timestamp":2,"value":2.3},
    {"timestamp":3,"value":1.9},{"timestamp":4,"value":2.5},
    {"timestamp":5,"value":2.0},{"timestamp":6,"value":2.2},
    {"timestamp":7,"value":2.4},{"timestamp":8,"value":2.1},
    {"timestamp":9,"value":1.8},{"timestamp":10,"value":2.3},
    {"timestamp":11,"value":2.0},{"timestamp":12,"value":2.2},
    {"timestamp":13,"value":2.1},{"timestamp":14,"value":2.4},
    {"timestamp":15,"value":94.7}
  ]' \
  memory_samples:='[]' \
  network_samples:='[]'
# Expected: cpu.is_anomaly=true, z_score >> 3.0
```

### Container
```bash
podman build -t edge-ai:latest .
podman run --rm -p 8000:8000 \
  -e ANOMALY_THRESHOLD=3.0 \
  edge-ai:latest
```

### Via compose (full stack)
```bash
# From the project root:
podman-compose up -d ai-engine
podman-compose logs -f ai-engine
```

---

## Configuration

All configuration is read from environment variables.

| Variable | Default | Description |
|----------|---------|-------------|
| `ANOMALY_THRESHOLD` | `3.0` | Z-score at which `is_anomaly` is set to true |
| `AI_ENGINE_HOST` | `0.0.0.0` | Uvicorn bind address |
| `AI_ENGINE_PORT` | `8000` | Uvicorn bind port |

### Tuning the threshold

The default threshold of 3.0 is standard for Z-score anomaly detection. Consider adjusting:

| Scenario | Recommended threshold | Reason |
|----------|-----------------------|--------|
| Production (low false positives) | 3.5 – 4.0 | Tighter baseline required before alerting |
| Development / demo | 2.5 – 3.0 | Easier to trigger with `sim-high-cpu.sh` |
| Network metrics on busy interface | 4.0 | Network traffic has heavier tails than CPU/memory |
| All metrics tight SLA | 2.5 | Detect deviations earlier at cost of more false positives |

---

## Request / Response Format

### `MetricSample`

```python
class MetricSample(BaseModel):
    timestamp: int    # Unix milliseconds (UTC) — used for ordering only
    value:     float  # Scalar metric value
```

Timestamps are carried through to the response for traceability but are not used in the analysis itself. The engine analyses the value series in the order received — the caller is responsible for sending samples in chronological order.

### `AnalyzeRequest`

The three sample arrays (`cpu_samples`, `memory_samples`, `network_samples`) are independent. They do not need to share timestamps. This is intentional: the backend queries each metric type separately from PostgreSQL, and the result sets may have slightly different lengths depending on collection history.

Empty arrays are valid: if a stream has fewer than `MIN_SAMPLES + 1` entries, the engine returns `reason: "insufficient_data"` for that stream without failing the entire request.

### `MetricAnalysis` fields

| Field | Type | Description |
|-------|------|-------------|
| `is_anomaly` | bool | `z_score >= threshold` |
| `z_score` | float | `\|current - mean\| / std`, rounded to 4dp |
| `risk_score` | float | `min(z / (threshold × 2), 1.0)`, rounded to 4dp |
| `mean` | float | Mean of baseline (all samples except last) |
| `std` | float | Population std of baseline |
| `current_value` | float | The last sample's value |
| `sample_count` | int | Total samples including current |
| `reason` | str | `"ok"` \| `"anomaly"` \| `"insufficient_data"` \| `"no_variance"` |

---

## Integration with the Backend

The backend's `AnomalyScheduler` (Kotlin) calls this service. The integration flow:

```
AnomalyScheduler.runCycle()
  → AiEngineClient.isHealthy()          GET /health → bool
  → repository.getMetricsHistory(...)   query PostgreSQL for last 60min
  → AiEngineClient.analyze(request)     POST /analyze → AnalyzeResponse?
  → logResult(deviceId, response)       WARN if anomaly detected
```

`AiEngineClient.analyze()` returns `null` on any failure (network error, timeout, non-200 status). The scheduler skips the device and continues — the AI engine is optional and its unavailability must never interrupt metric collection.

The Kotlin-side types in `backend/.../ai/AnomalyModels.kt` mirror these Pydantic models exactly. All field names use `@SerialName` to map between Kotlin camelCase and Python/JSON snake_case.

---

## Design Decisions

### Why `ZScoreAnalyzer` is stateless

Each `POST /analyze` call receives the complete sample window and returns the complete result. No per-device state is stored inside the analyser object. This means:

- The service can be restarted at any time without losing state
- Multiple instances could run behind a load balancer without session affinity
- Unit tests require no setup or teardown — just call the method with data

The state (metric history) lives in PostgreSQL, owned by the backend. The AI engine is a pure function of its input.

### Why a single shared `ZScoreAnalyzer` instance

`ZScoreAnalyzer` is stateless and has no mutable fields. Instantiating it once at startup and reusing it across requests is both correct and efficient — there is no per-request allocation or initialisation cost.

### Why `statistics.pstdev()` and not NumPy

`statistics` is the Python standard library. For Phase 4 the Z-score computation requires only `mean()` and `pstdev()` — importing NumPy for two function calls adds a dependency without benefit. NumPy is listed in `requirements.txt` for future use (Phase 6 algorithm upgrade) but is not used here.

### Why three separate sample arrays instead of one merged series

Merging CPU, memory, and network into a single time series would require timestamp alignment — handling gaps, different sampling rates, and missing values. Keeping them separate means the analysis of each metric is independent and straightforward. The risk of one metric's missing samples affecting another's analysis is eliminated.

### Why `POST /analyze` returns `null`-safe results per stream

An empty `cpu_samples` array returns `reason: "insufficient_data"` for the CPU stream but still analyses memory and network if those arrays have enough data. The entire request never fails because one stream is empty. This is important during agent startup, when different metric streams may accumulate data at slightly different rates.

---

## Testing

### Unit test — Z-score calculation
```python
from analyzer import ZScoreAnalyzer
from models import MetricSample

analyser = ZScoreAnalyzer(threshold=3.0)

# Build a flat baseline with one large spike
baseline = [MetricSample(timestamp=i, value=2.0) for i in range(20)]
spike    = MetricSample(timestamp=20, value=50.0)
samples  = baseline + [spike]

result = analyser.analyse_stream(samples)

assert result.is_anomaly        == True
assert result.z_score           >  3.0
assert result.mean              == 2.0
assert result.reason            == "anomaly"
```

### Unit test — guard conditions
```python
# Insufficient data
few = [MetricSample(timestamp=i, value=1.0) for i in range(5)]
r = analyser.analyse_stream(few)
assert r.reason == "insufficient_data"
assert r.is_anomaly == False

# No variance (flat signal)
flat = [MetricSample(timestamp=i, value=0.0) for i in range(20)]
r = analyser.analyse_stream(flat)
assert r.reason == "no_variance"
assert r.is_anomaly == False
```

### Integration test — end to end with backend
```bash
# Start the backend (with AI_ENGINE_URL set) and an agent.
# Wait for ~10 samples to build a baseline (~50s at 5s interval).
# Then inject CPU load:

./scripts/sim-high-cpu.sh dev1 60

# Watch ai-engine logs:
podman-compose logs -f ai-engine
# Expected within 30s:
# WARNING  ai_engine  ANOMALY  device=dev1  metric=cpu  z=5.xx  risk=0.xx
```

### Manual API test
```bash
# Confirm the service is up
http GET localhost:8000/health

# Interactive API docs (FastAPI built-in)
open http://localhost:8000/docs
```

---

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| `connection refused` on port 8000 | Service not running | `python main.py` or `podman-compose up ai-engine` |
| `reason: "insufficient_data"` always | Not enough history | Wait ~55s after agent starts (11 samples × 5s interval) |
| `reason: "no_variance"` on swap metrics | Swap never used | Expected — swap at 0 has no variance |
| `is_anomaly` never true on CPU load | Threshold too high or baseline not stable | Lower `ANOMALY_THRESHOLD` to 2.0 for testing; ensure agent ran 60s before load injection |
| `422 Unprocessable Entity` | Unexpected analysis error | Check `detail` field in response body; look at service logs |
| Backend logs `AI engine unreachable` | FastAPI process down | Start `python main.py` |
| Backend logs `AI engine returned 4xx` | Malformed request from backend | Check `AnomalyModels.kt` serialisation matches `models.py` |

---

<div align="center">

**[⬆ Back to Project Root](../README.md)**

</div>
