package app.edge_telemetry.storage

import app.edge_telemetry.models.*
import kotlinx.datetime.Instant

interface TelemetryRepository {
    suspend fun ingestCpu(
        deviceId: String,
        ts:       Instant,
        metric:   CpuMetric
    )

    suspend fun ingestMemory(
        deviceId: String,
        ts:       Instant,
        metric:   MemoryMetric
    )

    suspend fun ingestNetwork(
        deviceId: String,
        ts:       Instant,
        iface:    NetworkInterface
    )

    suspend fun getAllDevices(): List<Device>

    suspend fun getDevice(deviceId: String): DeviceDetails?

    suspend fun getLatestMetrics(deviceId: String): LatestMetrics?

    suspend fun getMetricsHistory(
        deviceId: String,
        from:     Instant,
        to:       Instant,
        type:     String
    ): List<MetricDataPoint>
}
