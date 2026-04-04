package app.edge_telemetry.grpc

import app.edge_telemetry.grpc.proto.Ack
import app.edge_telemetry.grpc.proto.Metric
import app.edge_telemetry.grpc.proto.MetricStatus
import app.edge_telemetry.grpc.proto.TelemetryServiceGrpcKt
import app.edge_telemetry.models.CpuMetric
import app.edge_telemetry.models.MemoryMetric
import app.edge_telemetry.models.NetworkInterface
import app.edge_telemetry.storage.TelemetryRepository
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory

class TelemetryGrpcService(
    private val repository: TelemetryRepository
) : TelemetryServiceGrpcKt.TelemetryServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(TelemetryGrpcService::class.java)

    override suspend fun streamMetrics(requests: Flow<Metric>): Ack {
        var received = 0L
        var deviceId = "unknown"

        try {
            requests
                .onEach { metric ->
                    if (received == 0L) {
                        deviceId = metric.deviceId
                        log.info("Agent connected: device_id={}", deviceId)
                    }

                    if (metric.status != MetricStatus.STATUS_OK) return@onEach

                    val ts = Instant.fromEpochMilliseconds(metric.timestamp)

                    when {
                        metric.hasCpu() -> repository.ingestCpu(
                            deviceId = metric.deviceId,
                            ts       = ts,
                            metric   = CpuMetric(
                                usagePercent = metric.cpu.usagePercent.toDouble(),
                                loadAvg1m    = metric.cpu.loadAvg1M.toDouble(),
                                loadAvg5m    = metric.cpu.loadAvg5M.toDouble(),
                                loadAvg15m   = metric.cpu.loadAvg15M.toDouble()
                            )
                        )

                        metric.hasMemory() -> repository.ingestMemory(
                            deviceId = metric.deviceId,
                            ts       = ts,
                            metric   = MemoryMetric(
                                totalKb           = metric.memory.totalKb.toLong(),
                                availableKb       = metric.memory.availableKb.toLong(),
                                usedKb            = metric.memory.usedKb.toLong(),
                                usagePercent      = metric.memory.usagePercent.toDouble(),
                                swapTotalKb       = metric.memory.swapTotalKb.toLong(),
                                swapFreeKb        = metric.memory.swapFreeKb.toLong(),
                                swapUsagePercent  = metric.memory.swapUsagePercent.toDouble()
                            )
                        )

                        metric.hasNetwork() -> repository.ingestNetwork(
                            deviceId = metric.deviceId,
                            ts       = ts,
                            iface    = NetworkInterface(
                                interfaceName = metric.network.interfaceName,
                                rxBytesPerSec = metric.network.rxBytesPerSec.toLong(),
                                txBytesPerSec = metric.network.txBytesPerSec.toLong(),
                                rxErrors      = metric.network.rxErrors.toLong(),
                                txErrors      = metric.network.txErrors.toLong(),
                                rxDropped     = metric.network.rxDropped.toLong(),
                                txDropped     = metric.network.txDropped.toLong()
                            )
                        )
                    }

                    received++

                    if (received % 100 == 0L) {
                        log.debug("device_id={} metrics_received={}", deviceId, received)
                    }
                }
                .catch { cause ->
                    log.warn("Stream error for device_id={}: {}", deviceId, cause.message)
                    throw StatusException(
                        Status.UNAVAILABLE.withDescription(cause.message).withCause(cause)
                    )
                }
                .collect()

        } finally {
            log.info("Agent disconnected: device_id={} total_metrics={}", deviceId, received)
        }

        return Ack.newBuilder()
            .setSuccess(true)
            .setMessage("Stream closed cleanly")
            .setMetricsReceived(received)
            .build()
    }
}
