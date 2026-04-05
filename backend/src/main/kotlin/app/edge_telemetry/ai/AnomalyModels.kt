package app.edge_telemetry.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MetricSample(
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("value")     val value:     Double
)

@Serializable
data class AnalyzeRequest(
    @SerialName("device_id")       val deviceId:       String,
    @SerialName("cpu_samples")     val cpuSamples:     List<MetricSample> = emptyList(),
    @SerialName("memory_samples")  val memorySamples:  List<MetricSample> = emptyList(),
    @SerialName("network_samples") val networkSamples: List<MetricSample> = emptyList()
)

@Serializable
data class MetricAnalysis(
    @SerialName("is_anomaly")    val isAnomaly:    Boolean = false,
    @SerialName("z_score")       val zScore:       Double  = 0.0,
    @SerialName("risk_score")    val riskScore:    Double  = 0.0,
    @SerialName("mean")          val mean:         Double  = 0.0,
    @SerialName("std")           val std:          Double  = 0.0,
    @SerialName("current_value") val currentValue: Double  = 0.0,
    @SerialName("sample_count")  val sampleCount:  Int     = 0,
    @SerialName("reason")        val reason:       String  = "ok"
)

@Serializable
data class AnalyzeResponse(
    @SerialName("device_id")    val deviceId:    String,
    @SerialName("analyzed_at")  val analyzedAt:  Long,
    @SerialName("results")      val results:     Map<String, MetricAnalysis>,
    @SerialName("overall_risk") val overallRisk: Double
)
