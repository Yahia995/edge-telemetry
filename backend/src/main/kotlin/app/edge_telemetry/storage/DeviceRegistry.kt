package app.edge_telemetry.storage

import app.edge_telemetry.grpc.proto.Metric
import app.edge_telemetry.grpc.proto.MetricStatus
import app.edge_telemetry.models.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * In-memory device registry and metrics storage.
 *
 * Two data paths feed into this registry:
 *   1. Mock data — seeded in init{} for demo/dev purposes.
 *   2. Real agent data — via [ingestMetric], called by the gRPC service
 *      for each Metric the agent streams.
 *
 * Thread-safety is provided by a single Mutex. All public suspend functions
 * acquire it before reading or writing shared state.
 */
class DeviceRegistry {
    private val mutex = Mutex()

    // deviceId -> current state snapshot
    private val devices = mutableMapOf<String, DeviceState>()

    // deviceId -> chronological list of snapshots (capped at 1 hour)
    private val metricsHistory = mutableMapOf<String, MutableList<MetricSnapshot>>()

    init {
        initializeMockDevices()
    }

    // ─── Real agent ingest ─────────────────────────────────────────────────

    /**
     * Called by [TelemetryGrpcService] for every Metric message received from
     * an agent. Routes the oneof payload to the appropriate partial update.
     *
     * Design note: the agent sends CPU, Memory, and Network as separate messages
     * within the same collection cycle (same timestamp). We accumulate them into
     * a single [MetricSnapshot] per cycle by keying on (deviceId, timestamp).
     * When all three arrive we have a complete snapshot; partial snapshots are
     * still stored so a slow or missing collector doesn't block the others.
     */
    suspend fun ingestMetric(metric: Metric) = mutex.withLock {
        // Skip anything that isn't STATUS_OK — the agent already logs the cause.
        if (metric.status != MetricStatus.STATUS_OK) return@withLock

        val deviceId = metric.deviceId
        val timestamp = Instant.fromEpochMilliseconds(metric.timestamp)

        // Ensure the device exists in the registry. If this is the first time
        // we've seen this agent, create a minimal DeviceState for it.
        devices.getOrPut(deviceId) {
            DeviceState(
                id = deviceId,
                name = deviceId,           // agent doesn't send a display name yet
                status = DeviceStatus.ONLINE,
                lastSeen = timestamp,
                cpuPercent = 0.0,
                memoryPercent = 0.0,
                networkRxMbps = 0.0,
                platform = "Linux",
                cpuCores = 0,              // unknown until we add a system info message
                uptime = 0
            )
        }

        val history = metricsHistory.getOrPut(deviceId) { mutableListOf() }

        // Find or create the snapshot bucket for this timestamp.
        // We use a 1-second tolerance so small clock jitter within a collection
        // cycle doesn't create duplicate buckets.
        val bucket = history.lastOrNull()?.takeIf {
            kotlin.math.abs((it.timestamp - timestamp).inWholeMilliseconds) < 1000
        } ?: MetricSnapshot(timestamp = timestamp).also { history.add(it) }

        // Apply the payload to the bucket and update the device summary.
        val device = devices[deviceId]!!

        when {
            metric.hasCpu() -> {
                val cpu = metric.cpu
                bucket.cpu = CpuMetric(
                    usagePercent = cpu.usagePercent.toDouble(),
                    loadAvg1m   = cpu.loadAvg1M.toDouble(),
                    loadAvg5m   = cpu.loadAvg5M.toDouble(),
                    loadAvg15m  = cpu.loadAvg15M.toDouble()
                )
                device.cpuPercent = cpu.usagePercent.toDouble().coerceIn(0.0, 100.0)
            }

            metric.hasMemory() -> {
                val mem = metric.memory
                bucket.memory = MemoryMetric(
                    totalKb       = mem.totalKb.toLong(),
                    availableKb   = mem.availableKb.toLong(),
                    usedKb        = mem.usedKb.toLong(),
                    usagePercent  = mem.usagePercent.toDouble()
                )
                device.memoryPercent = mem.usagePercent.toDouble().coerceIn(0.0, 100.0)
            }

            metric.hasNetwork() -> {
                val net = metric.network
                val iface = NetworkInterface(
                    interfaceName  = net.interfaceName,
                    rxBytesPerSec  = net.rxBytesPerSec.toLong(),
                    txBytesPerSec  = net.txBytesPerSec.toLong(),
                    rxErrors       = net.rxErrors.toLong(),
                    txErrors       = net.txErrors.toLong(),
                    rxDropped      = net.rxDropped.toLong(),
                    txDropped      = net.txDropped.toLong()
                )
                // Replace or append the interface entry for this bucket.
                val interfaces = bucket.network.toMutableList()
                val idx = interfaces.indexOfFirst { it.interfaceName == net.interfaceName }
                if (idx >= 0) interfaces[idx] = iface else interfaces.add(iface)
                bucket.network = interfaces

                // Update the device-level summary with the primary interface rate.
                // We use the interface with the highest rx rate as the representative.
                val primaryRxMbps = interfaces.maxOfOrNull { it.rxBytesPerSec }
                    ?.toDouble()?.div(1024 * 1024) ?: 0.0
                device.networkRxMbps = primaryRxMbps
            }
        }

        // Mark device as seen and online.
        device.lastSeen = timestamp
        device.status   = DeviceStatus.ONLINE

        // Evict history older than 1 hour to keep memory bounded.
        val oneHourAgo = Clock.System.now().minus(3600.seconds)
        history.removeAll { it.timestamp < oneHourAgo }
    }

    // ─── Read API (used by REST routes) ───────────────────────────────────

    suspend fun getAllDevices(): List<Device> = mutex.withLock {
        updateMockDeviceMetrics()
        devices.values.map { it.toDevice() }
    }

    suspend fun getDevice(deviceId: String): DeviceDetails? = mutex.withLock {
        devices[deviceId]?.toDeviceDetails()
    }

    suspend fun getLatestMetrics(deviceId: String): LatestMetrics? = mutex.withLock {
        val history = metricsHistory[deviceId] ?: return@withLock null
        val latest  = history.lastOrNull() ?: return@withLock null

        LatestMetrics(
            cpu       = latest.cpu       ?: return@withLock null,
            memory    = latest.memory    ?: return@withLock null,
            network   = latest.network,
            timestamp = latest.timestamp.toEpochMilliseconds()
        )
    }

    suspend fun getMetricsHistory(
        deviceId: String,
        from: Long,
        to: Long,
        type: String
    ): List<MetricDataPoint> = mutex.withLock {
        val history     = metricsHistory[deviceId] ?: return@withLock emptyList()
        val fromInstant = Instant.fromEpochMilliseconds(from)
        val toInstant   = Instant.fromEpochMilliseconds(to)

        history
            .filter { it.timestamp in fromInstant..toInstant }
            .mapNotNull { snapshot ->
                val value = when (type.lowercase()) {
                    "cpu"     -> snapshot.cpu?.usagePercent
                    "memory"  -> snapshot.memory?.usagePercent
                    "network" -> snapshot.network.firstOrNull()
                                     ?.rxBytesPerSec?.toDouble()?.div(1024 * 1024)
                    else      -> null
                } ?: return@mapNotNull null

                MetricDataPoint(
                    timestamp = snapshot.timestamp.toEpochMilliseconds(),
                    value     = value
                )
            }
    }

    // ─── Mock data ────────────────────────────────────────────────────────
    //
    // The mock devices let the dashboard work without a running agent.
    // They are never overwritten by ingestMetric — a real agent uses a
    // different device_id (e.g. "dev1" from --device-id flag).

    private fun initializeMockDevices() {
        val now = Clock.System.now()

        listOf(
            DeviceState("mock-prod-01",  "Production Server 01", DeviceStatus.ONLINE,
                        now, 45.2, 62.8, 12.5, "Linux", 8,  604800),
            DeviceState("mock-db-01",    "Database Server",      DeviceStatus.ONLINE,
                        now, 23.7, 78.3,  8.2, "Linux", 16, 1209600),
            DeviceState("mock-edge-03",  "Edge Device 03",       DeviceStatus.OFFLINE,
                        now.minus(300.seconds), 0.0, 0.0, 0.0, "Linux", 4, 0)
        ).forEach { device ->
            devices[device.id] = device
            if (device.status == DeviceStatus.ONLINE) {
                metricsHistory[device.id] = generateMockHistory(device)
            }
        }
    }

    private fun generateMockHistory(device: DeviceState): MutableList<MetricSnapshot> {
        val history  = mutableListOf<MetricSnapshot>()
        val now      = Clock.System.now()
        val points   = 720 // 1 hour at 5s intervals

        repeat(points) { i ->
            val t = now.minus((points - i - 1).seconds * 5)
            history.add(MetricSnapshot(
                timestamp = t,
                cpu = CpuMetric(
                    usagePercent = device.cpuPercent + (Random.nextDouble() - 0.5) * 15,
                    loadAvg1m    = Random.nextDouble() * 4,
                    loadAvg5m    = Random.nextDouble() * 3,
                    loadAvg15m   = Random.nextDouble() * 2
                ),
                memory = MemoryMetric(
                    totalKb      = 16_384_000,
                    availableKb  = (16_384_000 * (1 - device.memoryPercent / 100)).toLong(),
                    usedKb       = (16_384_000 * (device.memoryPercent / 100)).toLong(),
                    usagePercent = device.memoryPercent + (Random.nextDouble() - 0.5) * 8
                ),
                network = listOf(NetworkInterface(
                    interfaceName = "eth0",
                    rxBytesPerSec = (device.networkRxMbps * 1024 * 1024).toLong(),
                    txBytesPerSec = (Random.nextDouble() * 5 * 1024 * 1024).toLong()
                ))
            ))
        }
        return history
    }

    /** Advances mock device metrics so the dashboard shows live-looking data. */
    private fun updateMockDeviceMetrics() {
        val now = Clock.System.now()
        devices.values
            .filter { it.id.startsWith("mock-") && it.status == DeviceStatus.ONLINE }
            .forEach { device ->
                val history = metricsHistory[device.id] ?: return@forEach
                history.add(MetricSnapshot(
                    timestamp = now,
                    cpu = CpuMetric(
                        usagePercent = device.cpuPercent + (Random.nextDouble() - 0.5) * 10,
                        loadAvg1m    = Random.nextDouble() * 4,
                        loadAvg5m    = Random.nextDouble() * 3,
                        loadAvg15m   = Random.nextDouble() * 2
                    ),
                    memory = MemoryMetric(
                        totalKb      = 16_384_000,
                        availableKb  = (16_384_000 * (1 - device.memoryPercent / 100)).toLong(),
                        usedKb       = (16_384_000 * (device.memoryPercent / 100)).toLong(),
                        usagePercent = device.memoryPercent + (Random.nextDouble() - 0.5) * 5
                    ),
                    network = listOf(NetworkInterface(
                        interfaceName = "eth0",
                        rxBytesPerSec = (device.networkRxMbps * 1024 * 1024).toLong(),
                        txBytesPerSec = (Random.nextDouble() * 5 * 1024 * 1024).toLong()
                    ))
                ))
                history.removeAll { it.timestamp < now.minus(3600.seconds) }
                device.cpuPercent    = history.last().cpu!!.usagePercent.coerceIn(0.0, 100.0)
                device.memoryPercent = history.last().memory!!.usagePercent.coerceIn(0.0, 100.0)
                device.lastSeen      = now
            }
    }
}

// ─── Domain types ──────────────────────────────────────────────────────────

data class DeviceState(
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

/**
 * One collection cycle's worth of metrics for a device.
 *
 * Fields are nullable because the three metric types arrive as separate gRPC
 * messages. A snapshot starts empty and is filled as messages arrive.
 * Mock data always populates all three fields at once.
 */
data class MetricSnapshot(
    val timestamp: Instant,
    var cpu:       CpuMetric?              = null,
    var memory:    MemoryMetric?           = null,
    var network:   List<NetworkInterface>  = emptyList()
)
