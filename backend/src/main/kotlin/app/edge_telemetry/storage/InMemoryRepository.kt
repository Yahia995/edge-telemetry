package app.edge_telemetry.storage

import app.edge_telemetry.models.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class InMemoryRepository : TelemetryRepository {

    private val mutex = Mutex()

    private val devices = mutableMapOf<String, DeviceState>()

    private val history = mutableMapOf<String, MutableList<MetricSnapshot>>()

    init {
        seedMockDevices()
    }

    override suspend fun ingestCpu(
        deviceId: String,
        ts:       Instant,
        metric:   CpuMetric
    ) = mutex.withLock {
        val device = ensureDevice(deviceId, ts)
        val bucket = ensureBucket(deviceId, ts)

        bucket.cpu           = metric
        device.cpuPercent    = metric.usagePercent.coerceIn(0.0, 100.0)
        device.lastSeen      = ts
        device.status        = DeviceStatus.ONLINE

        evictOldSnapshots(deviceId)
    }

    override suspend fun ingestMemory(
        deviceId: String,
        ts:       Instant,
        metric:   MemoryMetric
    ) = mutex.withLock {
        val device = ensureDevice(deviceId, ts)
        val bucket = ensureBucket(deviceId, ts)

        bucket.memory        = metric
        device.memoryPercent = metric.usagePercent.coerceIn(0.0, 100.0)
        device.lastSeen      = ts
        device.status        = DeviceStatus.ONLINE

        evictOldSnapshots(deviceId)
    }

    override suspend fun ingestNetwork(
        deviceId: String,
        ts:       Instant,
        iface:    NetworkInterface
    ) = mutex.withLock {
        val device  = ensureDevice(deviceId, ts)
        val bucket  = ensureBucket(deviceId, ts)

        val interfaces = bucket.network.toMutableList()
        val idx = interfaces.indexOfFirst { it.interfaceName == iface.interfaceName }
        if (idx >= 0) interfaces[idx] = iface else interfaces.add(iface)
        bucket.network = interfaces

        // Update device-level summary with the highest-rx interface.
        device.networkRxMbps = interfaces
            .maxOfOrNull { it.rxBytesPerSec }
            ?.toDouble()?.div(1024 * 1024) ?: 0.0
        device.lastSeen      = ts
        device.status        = DeviceStatus.ONLINE

        evictOldSnapshots(deviceId)
    }

    override suspend fun getAllDevices(): List<Device> = mutex.withLock {
        tickMockDevices()
        devices.values.map { it.toDevice() }
    }

    override suspend fun getDevice(deviceId: String): DeviceDetails? = mutex.withLock {
        devices[deviceId]?.toDeviceDetails()
    }

    override suspend fun getLatestMetrics(deviceId: String): LatestMetrics? = mutex.withLock {
        val snapshots = history[deviceId] ?: return@withLock null
        val latest    = snapshots.lastOrNull() ?: return@withLock null

        LatestMetrics(
            cpu       = latest.cpu       ?: return@withLock null,
            memory    = latest.memory    ?: return@withLock null,
            network   = latest.network,
            timestamp = latest.timestamp.toEpochMilliseconds()
        )
    }

    override suspend fun getMetricsHistory(
        deviceId: String,
        from:     Instant,
        to:       Instant,
        type:     String
    ): List<MetricDataPoint> = mutex.withLock {
        val snapshots = history[deviceId] ?: return@withLock emptyList()

        snapshots
            .filter { it.timestamp in from..to }
            .mapNotNull { snapshot ->
                val value = when (type.lowercase()) {
                    "cpu"     -> snapshot.cpu?.usagePercent
                    "memory"  -> snapshot.memory?.usagePercent
                    "network" -> snapshot.network
                                     .maxOfOrNull { it.rxBytesPerSec }
                                     ?.toDouble()?.div(1024 * 1024)
                    else      -> null
                } ?: return@mapNotNull null

                MetricDataPoint(
                    timestamp = snapshot.timestamp.toEpochMilliseconds(),
                    value     = value
                )
            }
    }

    private fun ensureDevice(deviceId: String, ts: Instant): DeviceState =
        devices.getOrPut(deviceId) {
            DeviceState(
                id             = deviceId,
                name           = deviceId,   // display name unknown until Phase 4 system-info
                status         = DeviceStatus.ONLINE,
                lastSeen       = ts,
                cpuPercent     = 0.0,
                memoryPercent  = 0.0,
                networkRxMbps  = 0.0,
                platform       = "Linux",
                cpuCores       = 0,          // unknown until Phase 4
                uptime         = 0
            )
        }

    private fun ensureBucket(deviceId: String, ts: Instant): MetricSnapshot {
        val snapshots = history.getOrPut(deviceId) { mutableListOf() }
        val existing  = snapshots.lastOrNull()?.takeIf {
            kotlin.math.abs((it.timestamp - ts).inWholeMilliseconds) < 1_000
        }
        return existing ?: MetricSnapshot(timestamp = ts).also { snapshots.add(it) }
    }

    private fun evictOldSnapshots(deviceId: String) {
        val cutoff = Clock.System.now().minus(3_600.seconds)
        history[deviceId]?.removeAll { it.timestamp < cutoff }
    }

    private fun seedMockDevices() {
        val now = Clock.System.now()
        listOf(
            DeviceState("mock-prod-01",  "Production Server 01", DeviceStatus.ONLINE,
                        now, 45.2, 62.8, 12.5, "Linux", 8,  604_800),
            DeviceState("mock-db-01",    "Database Server",      DeviceStatus.ONLINE,
                        now, 23.7, 78.3,  8.2, "Linux", 16, 1_209_600),
            DeviceState("mock-edge-03",  "Edge Device 03",       DeviceStatus.OFFLINE,
                        now.minus(300.seconds), 0.0, 0.0, 0.0, "Linux", 4, 0)
        ).forEach { device ->
            devices[device.id] = device
            if (device.status == DeviceStatus.ONLINE) {
                history[device.id] = buildMockHistory(device)
            }
        }
    }

    private fun buildMockHistory(device: DeviceState): MutableList<MetricSnapshot> {
        val points = 720   // 1 hour at 5 s intervals
        val now    = Clock.System.now()
        return (0 until points).mapTo(mutableListOf()) { i ->
            val t = now.minus(((points - i - 1) * 5).seconds)
            MetricSnapshot(
                timestamp = t,
                cpu = CpuMetric(
                    usagePercent = (device.cpuPercent + (Random.nextDouble() - 0.5) * 15)
                                       .coerceIn(0.0, 100.0),
                    loadAvg1m    = Random.nextDouble() * 4,
                    loadAvg5m    = Random.nextDouble() * 3,
                    loadAvg15m   = Random.nextDouble() * 2
                ),
                memory = MemoryMetric(
                    totalKb      = 16_384_000,
                    availableKb  = (16_384_000 * (1 - device.memoryPercent / 100)).toLong(),
                    usedKb       = (16_384_000 * (device.memoryPercent / 100)).toLong(),
                    usagePercent = (device.memoryPercent + (Random.nextDouble() - 0.5) * 8)
                                       .coerceIn(0.0, 100.0)
                ),
                network = listOf(NetworkInterface(
                    interfaceName = "eth0",
                    rxBytesPerSec = (device.networkRxMbps * 1_024 * 1_024).toLong(),
                    txBytesPerSec = (Random.nextDouble() * 5 * 1_024 * 1_024).toLong()
                ))
            )
        }
    }

    private fun tickMockDevices() {
        val now = Clock.System.now()
        devices.values
            .filter { it.id.startsWith("mock-") && it.status == DeviceStatus.ONLINE }
            .forEach { device ->
                val snapshots = history[device.id] ?: return@forEach
                snapshots.add(MetricSnapshot(
                    timestamp = now,
                    cpu = CpuMetric(
                        usagePercent = (device.cpuPercent + (Random.nextDouble() - 0.5) * 10)
                                           .coerceIn(0.0, 100.0),
                        loadAvg1m    = Random.nextDouble() * 4,
                        loadAvg5m    = Random.nextDouble() * 3,
                        loadAvg15m   = Random.nextDouble() * 2
                    ),
                    memory = MemoryMetric(
                        totalKb      = 16_384_000,
                        availableKb  = (16_384_000 * (1 - device.memoryPercent / 100)).toLong(),
                        usedKb       = (16_384_000 * (device.memoryPercent / 100)).toLong(),
                        usagePercent = (device.memoryPercent + (Random.nextDouble() - 0.5) * 5)
                                           .coerceIn(0.0, 100.0)
                    ),
                    network = listOf(NetworkInterface(
                        interfaceName = "eth0",
                        rxBytesPerSec = (device.networkRxMbps * 1_024 * 1_024).toLong(),
                        txBytesPerSec = (Random.nextDouble() * 5 * 1_024 * 1_024).toLong()
                    ))
                ))
                snapshots.removeAll { it.timestamp < now.minus(3_600.seconds) }
                device.cpuPercent    = snapshots.last().cpu!!.usagePercent
                device.memoryPercent = snapshots.last().memory!!.usagePercent
                device.lastSeen      = now
            }
    }
}

internal data class DeviceState(
    val id:             String,
    val name:           String,
    var status:         DeviceStatus,
    var lastSeen:       Instant,
    var cpuPercent:     Double,
    var memoryPercent:  Double,
    var networkRxMbps:  Double,
    val platform:       String,
    val cpuCores:       Int,
    var uptime:         Long
) {
    fun toDevice() = Device(
        id             = id,
        name           = name,
        status         = status,
        lastSeen       = lastSeen.toString(),
        cpuPercent     = cpuPercent,
        memoryPercent  = memoryPercent,
        networkRxMbps  = networkRxMbps
    )

    fun toDeviceDetails() = DeviceDetails(
        id       = id,
        name     = name,
        status   = status,
        lastSeen = lastSeen.toString(),
        uptime   = uptime,
        platform = platform,
        cpuCores = cpuCores
    )
}

internal data class MetricSnapshot(
    val timestamp: Instant,
    var cpu:       CpuMetric?             = null,
    var memory:    MemoryMetric?          = null,
    var network:   List<NetworkInterface> = emptyList()
)
