from __future__ import annotations

import logging
import os
import time

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from analyzer import ZScoreAnalyzer
from models import AnalyzeRequest, AnalyzeResponse

logging.basicConfig(
    level   = logging.INFO,
    format  = "%(asctime)s  %(levelname)-8s  %(name)s  %(message)s",
    datefmt = "%H:%M:%S",
)
log = logging.getLogger("ai_engine")

ANOMALY_THRESHOLD: float = float(os.getenv("ANOMALY_THRESHOLD", "3.0"))
HOST:              str   = os.getenv("AI_ENGINE_HOST", "0.0.0.0")
PORT:              int   = int(os.getenv("AI_ENGINE_PORT", "8000"))

app = FastAPI(
    title       = "Edge Telemetry AI Engine",
    description = "Z-score anomaly detection for edge device metric streams.",
    version     = "0.1.0",
)

_analyser = ZScoreAnalyzer(threshold=ANOMALY_THRESHOLD)

log.info(
    "AI engine initialised  threshold=%.1f  host=%s  port=%d",
    ANOMALY_THRESHOLD, HOST, PORT,
)

@app.post("/analyze", response_model=AnalyzeResponse)
async def analyze(request: AnalyzeRequest) -> AnalyzeResponse:
    log.info(
        "analyze  device=%s  cpu=%d  mem=%d  net=%d samples",
        request.device_id,
        len(request.cpu_samples),
        len(request.memory_samples),
        len(request.network_samples),
    )

    try:
        results, overall_risk = _analyser.analyse_device(
            cpu_samples     = request.cpu_samples,
            memory_samples  = request.memory_samples,
            network_samples = request.network_samples,
        )
    except Exception as exc:
        log.exception("Analysis failed for device %s", request.device_id)
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    for metric_name, result in results.items():
        if result.is_anomaly:
            log.warning(
                "ANOMALY  device=%s  metric=%s  z=%.2f  risk=%.2f  "
                "current=%.2f  mean=%.2f  std=%.2f",
                request.device_id, metric_name,
                result.z_score, result.risk_score,
                result.current_value, result.mean, result.std,
            )

    response = AnalyzeResponse(
        device_id    = request.device_id,
        analyzed_at  = int(time.time() * 1000),
        results      = results,
        overall_risk = round(overall_risk, 4),
    )

    log.info(
        "done  device=%s  overall_risk=%.4f  anomalies=%s",
        request.device_id,
        overall_risk,
        [k for k, v in results.items() if v.is_anomaly] or "none",
    )

    return response


@app.get("/health")
async def health() -> JSONResponse:
    """Liveness probe. Returns 200 as long as the process is running."""
    return JSONResponse(content={
        "status":    "healthy",
        "version":   "0.1.0",
        "threshold": ANOMALY_THRESHOLD,
        "timestamp": int(time.time() * 1000),
    })


if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host    = HOST,
        port    = PORT,
        reload  = False,
        log_level = "info",
    )
