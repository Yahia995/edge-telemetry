package app.edge_telemetry.models

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceStatus {
    ONLINE,
    OFFLINE
}

@Serializable
data class Device(
    val id: String,
    val name: String,
    val status: DeviceStatus,
    val lastSeen: String, // ISO timestamp
    val cpuPercent: Double,
    val memoryPercent: Double,
    val networkRxMbps: Double
)

@Serializable
data class DeviceDetails(
    val id: String,
    val name: String,
    val status: DeviceStatus,
    val lastSeen: String,
    val uptime: Long, // seconds
    val platform: String,
    val cpuCores: Int
)

@Serializable
data class CpuMetric(
    val usagePercent: Double,
    val loadAvg1m: Double,
    val loadAvg5m: Double,
    val loadAvg15m: Double
)

@Serializable
data class MemoryMetric(
    val totalKb: Long,
    val availableKb: Long,
    val usedKb: Long,
    val usagePercent: Double,
    val swapTotalKb: Long? = null,
    val swapFreeKb: Long? = null,
    val swapUsagePercent: Double? = null
)

@Serializable
data class NetworkInterface(
    val interfaceName: String,
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long,
    val rxErrors: Long? = null,
    val txErrors: Long? = null,
    val rxDropped: Long? = null,
    val txDropped: Long? = null
)

@Serializable
data class LatestMetrics(
    val cpu: CpuMetric,
    val memory: MemoryMetric,
    val network: List<NetworkInterface>,
    val timestamp: Long // Unix milliseconds
)

@Serializable
data class MetricDataPoint(
    val timestamp: Long,
    val value: Double
)

@Serializable
data class MetricsHistoryResponse(
    val deviceId: String,
    val type: String, // "cpu", "memory", "network"
    val metrics: List<MetricDataPoint>
)

@Serializable
data class DevicesResponse(
    val devices: List<Device>
)

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: Long
)

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String? = null
)

@Serializable
data class ApiInfoResponse(
    val name: String,
    val version: String,
    val endpoints: Map<String, String>
)