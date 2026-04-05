from __future__ import annotations

import statistics
import time
from dataclasses import dataclass

from models import MetricAnalysis, MetricSample

DEFAULT_THRESHOLD: float = 3.0
MIN_SAMPLES:       int   = 10       # below this, refuse to analyse
MIN_STD:           float = 1e-6     # below this, treat signal as flat

@dataclass
class _AnalysisResult:
    is_anomaly:    bool
    z_score:       float
    risk_score:    float
    mean:          float
    std:           float
    current_value: float
    sample_count:  int
    reason:        str

    def to_model(self) -> MetricAnalysis:
        return MetricAnalysis(
            is_anomaly    = self.is_anomaly,
            z_score       = round(self.z_score, 4),
            risk_score    = round(self.risk_score, 4),
            mean          = round(self.mean, 4),
            std           = round(self.std, 4),
            current_value = round(self.current_value, 4),
            sample_count  = self.sample_count,
            reason        = self.reason,
        )

class ZScoreAnalyzer:
    def __init__(self, threshold: float = DEFAULT_THRESHOLD) -> None:
        self.threshold = threshold

    def analyse_stream(
        self,
        samples: list[MetricSample],
        metric_name: str = "",
    ) -> MetricAnalysis:

        n = len(samples)

        if n < MIN_SAMPLES + 1:
            return _AnalysisResult(
                is_anomaly    = False,
                z_score       = 0.0,
                risk_score    = 0.0,
                mean          = 0.0,
                std           = 0.0,
                current_value = samples[-1].value if samples else 0.0,
                sample_count  = n,
                reason        = "insufficient_data",
            ).to_model()

        baseline = [s.value for s in samples[:-1]]
        current  = samples[-1].value

        mean = statistics.mean(baseline)
        std  = statistics.pstdev(baseline)   
        if std < MIN_STD:
            return _AnalysisResult(
                is_anomaly    = False,
                z_score       = 0.0,
                risk_score    = 0.0,
                mean          = round(mean, 4),
                std           = 0.0,
                current_value = current,
                sample_count  = n,
                reason        = "no_variance",
            ).to_model()

        z          = abs(current - mean) / std
        risk_score = min(z / (self.threshold * 2.0), 1.0)
        is_anomaly = z >= self.threshold

        return _AnalysisResult(
            is_anomaly    = is_anomaly,
            z_score       = z,
            risk_score    = risk_score,
            mean          = mean,
            std           = std,
            current_value = current,
            sample_count  = n,
            reason        = "anomaly" if is_anomaly else "ok",
        ).to_model()

    def analyse_device(
        self,
        cpu_samples:     list[MetricSample],
        memory_samples:  list[MetricSample],
        network_samples: list[MetricSample],
    ) -> tuple[dict[str, MetricAnalysis], float]:
        results = {
            "cpu":     self.analyse_stream(cpu_samples,     "cpu"),
            "memory":  self.analyse_stream(memory_samples,  "memory"),
            "network": self.analyse_stream(network_samples, "network"),
        }
        overall_risk = max(r.risk_score for r in results.values())
        return results, overall_risk
