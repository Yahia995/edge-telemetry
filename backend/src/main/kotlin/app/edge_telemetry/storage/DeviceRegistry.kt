package app.edge_telemetry.storage

import app.edge_telemetry.models.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * In-memory device registry and metrics storage.
 * Thread-safe using Mutex for concurrent access.
 */
class DeviceRegistry {
    private val mutex = Mutex()
    private val devices = mutableMapOf<String, DeviceState>()
    private val metricsHistory = mutableMapOf<String, MutableList<MetricSnapshot>>()
    
    init {
        // Initialize with mock devices
        initializeMockDevices()
    }
    
    private fun initializeMockDevices() {
        val now = Clock.System.now()
        
        listOf(
            DeviceState(
                id = "dev1",
                name = "Production Server 01",
                status = DeviceStatus.ONLINE,
                lastSeen = now,
                cpuPercent = 45.2,
                memoryPercent = 62.8,
                networkRxMbps = 12.5,
                platform = "Linux",
                cpuCores = 8,
                uptime = 604800 // 7 days
            ),
            DeviceState(
                id = "dev2",
                name = "Database Server",
                status = DeviceStatus.ONLINE,
                lastSeen = now,
                cpuPercent = 23.7,
                memoryPercent = 78.3,
                networkRxMbps = 8.2,
                platform = "Linux",
                cpuCores = 16,
                uptime = 1209600 // 14 days
            ),
            DeviceState(
                id = "dev3",
                name = "Edge Device 03",
                status = DeviceStatus.OFFLINE,
                lastSeen = now.minus(5.seconds * 60), // 5 min ago
                cpuPercent = 0.0,
                memoryPercent = 0.0,
                networkRxMbps = 0.0,
                platform = "Linux",
                cpuCores = 4,
                uptime = 0
            )
        ).forEach { device ->
            devices[device.id] = device
            
            // Generate historical metrics
            if (device.status == DeviceStatus.ONLINE) {
                generateHistoricalMetrics(device.id, device)
            }
        }
    }
    
    private fun generateHistoricalMetrics(deviceId: String, device: DeviceState) {
        val history = mutableListOf<MetricSnapshot>()
        val now = Clock.System.now()
        val interval = 5.seconds
        val points = 720 // 1 hour of data (720 * 5s = 3600s)
        
        for (i in 0 until points) {
            val timestamp = now.minus(interval * (points - i - 1))
            
            history.add(
                MetricSnapshot(
                    timestamp = timestamp,
                    cpu = CpuMetric(
                        usagePercent = device.cpuPercent + (Random.nextDouble() - 0.5) * 15,
                        loadAvg1m = Random.nextDouble() * 4,
                        loadAvg5m = Random.nextDouble() * 3,
                        loadAvg15m = Random.nextDouble() * 2
                    ),
                    memory = MemoryMetric(
                        totalKb = 16384000,
                        availableKb = (16384000 * (1 - device.memoryPercent / 100)).toLong(),
                        usedKb = (16384000 * (device.memoryPercent / 100)).toLong(),
                        usagePercent = device.memoryPercent + (Random.nextDouble() - 0.5) * 8
                    ),
                    network = listOf(
                        NetworkInterface(
                            interfaceName = "eth0",
                            rxBytesPerSec = (device.networkRxMbps * 1024 * 1024).toLong(),
                            txBytesPerSec = (Random.nextDouble() * 5 * 1024 * 1024).toLong()
                        )
                    )
                )
            )
        }
        
        metricsHistory[deviceId] = history
    }
    
    suspend fun getAllDevices(): List<Device> = mutex.withLock {
        // Update devices with latest metrics
        updateDeviceMetrics()
        
        devices.values.map { it.toDevice() }
    }
    
    suspend fun getDevice(deviceId: String): DeviceDetails? = mutex.withLock {
        devices[deviceId]?.toDeviceDetails()
    }
    
    suspend fun getLatestMetrics(deviceId: String): LatestMetrics? = mutex.withLock {
        val history = metricsHistory[deviceId] ?: return@withLock null
        val latest = history.lastOrNull() ?: return@withLock null
        
        LatestMetrics(
            cpu = latest.cpu,
            memory = latest.memory,
            network = latest.network,
            timestamp = latest.timestamp.toEpochMilliseconds()
        )
    }
    
    suspend fun getMetricsHistory(
        deviceId: String,
        from: Long,
        to: Long,
        type: String
    ): List<MetricDataPoint> = mutex.withLock {
        val history = metricsHistory[deviceId] ?: return@withLock emptyList()
        
        val fromInstant = Instant.fromEpochMilliseconds(from)
        val toInstant = Instant.fromEpochMilliseconds(to)
        
        history
            .filter { it.timestamp >= fromInstant && it.timestamp <= toInstant }
            .map { snapshot ->
                val value = when (type.lowercase()) {
                    "cpu" -> snapshot.cpu.usagePercent
                    "memory" -> snapshot.memory.usagePercent
                    "network" -> snapshot.network.firstOrNull()?.rxBytesPerSec?.toDouble()?.div(1024 * 1024) ?: 0.0
                    else -> 0.0
                }
                MetricDataPoint(
                    timestamp = snapshot.timestamp.toEpochMilliseconds(),
                    value = value
                )
            }
    }
    
    private fun updateDeviceMetrics() {
        val now = Clock.System.now()
        
        devices.values.forEach { device ->
            if (device.status == DeviceStatus.ONLINE) {
                // Add new metric snapshot
                val history = metricsHistory[device.id] ?: return@forEach
                
                val latest = history.lastOrNull() ?: return@forEach
                
                val newSnapshot = MetricSnapshot(
                    timestamp = now,
                    cpu = CpuMetric(
                        usagePercent = device.cpuPercent + (Random.nextDouble() - 0.5) * 10,
                        loadAvg1m = Random.nextDouble() * 4,
                        loadAvg5m = Random.nextDouble() * 3,
                        loadAvg15m = Random.nextDouble() * 2
                    ),
                    memory = MemoryMetric(
                        totalKb = 16384000,
                        availableKb = (16384000 * (1 - device.memoryPercent / 100)).toLong(),
                        usedKb = (16384000 * (device.memoryPercent / 100)).toLong(),
                        usagePercent = device.memoryPercent + (Random.nextDouble() - 0.5) * 5
                    ),
                    network = listOf(
                        NetworkInterface(
                            interfaceName = "eth0",
                            rxBytesPerSec = (device.networkRxMbps * 1024 * 1024).toLong(),
                            txBytesPerSec = (Random.nextDouble() * 5 * 1024 * 1024).toLong()
                        )
                    )
                )
                
                history.add(newSnapshot)
                
                // Keep only last hour of data
                val oneHourAgo = now.minus(3600.seconds)
                history.removeAll { it.timestamp < oneHourAgo }
                
                // Update device with latest values
                device.cpuPercent = newSnapshot.cpu.usagePercent.coerceIn(0.0, 100.0)
                device.memoryPercent = newSnapshot.memory.usagePercent.coerceIn(0.0, 100.0)
                device.lastSeen = now
            }
        }
    }
}

data class DeviceState(
    val id: String,
    val name: String,
    var status: DeviceStatus,
    var lastSeen: Instant,
    var cpuPercent: Double,
    var memoryPercent: Double,
    var networkRxMbps: Double,
    val platform: String,
    val cpuCores: Int,
    var uptime: Long
) {
    fun toDevice() = Device(
        id = id,
        name = name,
        status = status,
        lastSeen = lastSeen.toString(),
        cpuPercent = cpuPercent,
        memoryPercent = memoryPercent,
        networkRxMbps = networkRxMbps
    )
    
    fun toDeviceDetails() = DeviceDetails(
        id = id,
        name = name,
        status = status,
        lastSeen = lastSeen.toString(),
        uptime = uptime,
        platform = platform,
        cpuCores = cpuCores
    )
}

data class MetricSnapshot(
    val timestamp: Instant,
    val cpu: CpuMetric,
    val memory: MemoryMetric,
    val network: List<NetworkInterface>
)
