package app.edge_telemetry.ai

import app.edge_telemetry.models.MetricDataPoint
import app.edge_telemetry.storage.TelemetryRepository
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AnomalyScheduler(
    private val repository:      TelemetryRepository,
    private val aiClient:        AiEngineClient,
    private val intervalSeconds: Long = 60L,
    private val windowMinutes:   Int  = 60
) {
    private val log = LoggerFactory.getLogger(AnomalyScheduler::class.java)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var job: Job? = null

    private val actionableRiskThreshold = 0.8

    fun start() {
        job = scope.launch {
            log.info(
                "Anomaly scheduler started  interval={}s  window={}min",
                intervalSeconds, windowMinutes
            )

            delay(intervalSeconds.seconds)

            while (isActive) {
                runCycle()
                delay(intervalSeconds.seconds)
            }
        }
    }

    fun stop() {
        log.info("Anomaly scheduler stopping")
        scope.cancel()
    }

    private suspend fun runCycle() {
        if (!aiClient.isHealthy()) {
            log.warn("AI engine unreachable — skipping analysis cycle")
            return
        }

        val now  = Clock.System.now()
        val from = now.minus(windowMinutes.minutes)

        val deviceIds = repository.getAllDevices()
            .filter { !it.id.startsWith("mock-") }
            .map    { it.id }

        if (deviceIds.isEmpty()) {
            log.debug("No real devices known — skipping analysis cycle")
            return
        }

        log.info("Analysis cycle  devices={}  window=[{} → {}]", deviceIds.size, from, now)

        val jobs = deviceIds.map { deviceId ->
            scope.launch {
                analyseDevice(deviceId, from, now)
            }
        }
        jobs.forEach { it.join() }
    }

    private suspend fun analyseDevice(
        deviceId: String,
        from:     Instant,
        to:       Instant
    ) {
        val cpuSamples     = repository.getMetricsHistory(deviceId, from, to, "cpu")
        val memorySamples  = repository.getMetricsHistory(deviceId, from, to, "memory")
        val networkSamples = repository.getMetricsHistory(deviceId, from, to, "network")

        if (cpuSamples.isEmpty() && memorySamples.isEmpty() && networkSamples.isEmpty()) {
            log.debug("device={} has no metric history in window — skipping", deviceId)
            return
        }

        val request = AnalyzeRequest(
            deviceId       = deviceId,
            cpuSamples     = cpuSamples.toMetricSamples(),
            memorySamples  = memorySamples.toMetricSamples(),
            networkSamples = networkSamples.toMetricSamples()
        )

        val response = aiClient.analyze(request) ?: return  
        logResult(deviceId, response)
    }

    private fun logResult(deviceId: String, response: AnalyzeResponse) {
        val anomalies = response.results.filter { (_, v) -> v.isAnomaly }

        if (anomalies.isEmpty()) {
            log.debug(
                "device={}  overall_risk={:.4f}  status=normal",
                deviceId, response.overallRisk
            )
            return
        }

        anomalies.forEach { (metric, analysis) ->
            log.warn(
                "ANOMALY  device={}  metric={}  z={:.2f}  risk={:.2f}  " +
                "current={:.2f}  mean={:.2f}  std={:.2f}",
                deviceId, metric,
                analysis.zScore, analysis.riskScore,
                analysis.currentValue, analysis.mean, analysis.std
            )
        }

        if (response.overallRisk >= actionableRiskThreshold) {
            log.warn(
                "ACTIONABLE  device={}  overall_risk={:.2f}  " +
                "[control feedback not yet implemented — Phase 4 part 2]",
                deviceId, response.overallRisk
            )
        }
    }

    private fun List<MetricDataPoint>.toMetricSamples(): List<MetricSample> =
        map { MetricSample(timestamp = it.timestamp, value = it.value) }
}
