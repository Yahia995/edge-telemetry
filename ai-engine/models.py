from __future__ import annotations

from pydantic import BaseModel, Field

class MetricSample(BaseModel):
    timestamp: int   = Field(description="Unix milliseconds (UTC)")
    value:     float = Field(description="Scalar metric value")


class AnalyzeRequest(BaseModel):
    device_id:       str                = Field(description="Agent device ID")
    cpu_samples:     list[MetricSample] = Field(default_factory=list)
    memory_samples:  list[MetricSample] = Field(default_factory=list)
    network_samples: list[MetricSample] = Field(default_factory=list)


class MetricAnalysis(BaseModel):
    is_anomaly:    bool  = False
    z_score:       float = 0.0
    risk_score:    float = 0.0
    mean:          float = 0.0
    std:           float = 0.0
    current_value: float = 0.0
    sample_count:  int   = 0
    reason:        str   = "ok"   # "ok" | "anomaly" | "insufficient_data" | "no_variance"


class AnalyzeResponse(BaseModel):
    device_id:    str                      = Field(description="Echoed from request")
    analyzed_at:  int                      = Field(description="Unix milliseconds (UTC)")
    results:      dict[str, MetricAnalysis]
    overall_risk: float                    = Field(ge=0.0, le=1.0)
