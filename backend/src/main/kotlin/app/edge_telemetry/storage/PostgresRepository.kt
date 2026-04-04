package app.edge_telemetry.storage

import app.edge_telemetry.models.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory

private object DevicesTable : Table("devices") {
    val id             = text("id")
    val name           = text("name")
    val status         = text("status").default("offline")
    val lastSeen       = timestamp("last_seen").nullable()
    val cpuPercent     = double("cpu_percent").nullable()
    val memoryPercent  = double("memory_percent").nullable()
    val networkRxMbps  = double("network_rx_mbps").nullable()
    val platform       = text("platform").default("Linux")
    val cpuCores       = integer("cpu_cores").default(0)
    val uptimeSeconds  = long("uptime_seconds").default(0)
    override val primaryKey = PrimaryKey(id)
}

private object MetricCpuTable : Table("metric_cpu") {
    val id           = long("id").autoIncrement()
    val deviceId     = text("device_id")
        .references(DevicesTable.id, onDelete = ReferenceOption.CASCADE)
    val ts           = timestamp("ts")
    val usagePercent = double("usage_percent")
    val loadAvg1m    = double("load_avg_1m")
    val loadAvg5m    = double("load_avg_5m")
    val loadAvg15m   = double("load_avg_15m")
    override val primaryKey = PrimaryKey(id)
}

private object MetricMemoryTable : Table("metric_memory") {
    val id           = long("id").autoIncrement()
    val deviceId     = text("device_id")
        .references(DevicesTable.id, onDelete = ReferenceOption.CASCADE)
    val ts           = timestamp("ts")
    val totalKb      = long("total_kb")
    val availableKb  = long("available_kb")
    val usedKb       = long("used_kb")
    val usagePercent = double("usage_percent")
    val swapTotalKb  = long("swap_total_kb")
    val swapFreeKb   = long("swap_free_kb")
    val swapUsagePct = double("swap_usage_pct")
    override val primaryKey = PrimaryKey(id)
}

private object MetricNetworkTable : Table("metric_network") {
    val id             = long("id").autoIncrement()
    val deviceId       = text("device_id")
        .references(DevicesTable.id, onDelete = ReferenceOption.CASCADE)
    val ts             = timestamp("ts")
    val interfaceName  = text("interface_name")
    val rxBytesPerSec  = long("rx_bytes_per_sec")
    val txBytesPerSec  = long("tx_bytes_per_sec")
    val rxErrors       = long("rx_errors")
    val txErrors       = long("tx_errors")
    val rxDropped      = long("rx_dropped")
    val txDropped      = long("tx_dropped")
    override val primaryKey = PrimaryKey(id)
}

class PostgresRepository private constructor(
    private val database: Database
) : TelemetryRepository {

    private val log = LoggerFactory.getLogger(PostgresRepository::class.java)

    companion object {
        fun create(
            url:          String,
            user:         String,
            password:     String,
            poolSize:     Int = 10
        ): PostgresRepository {
            val dataSource = buildDataSource(url, user, password, poolSize)
            val database   = Database.connect(dataSource)
            return PostgresRepository(database)
        }

        private fun buildDataSource(
            url:      String,
            user:     String,
            password: String,
            poolSize: Int
        ): HikariDataSource {
            val config = HikariConfig().apply {
                jdbcUrl          = url
                username         = user
                this.password    = password
                maximumPoolSize  = poolSize
                minimumIdle      = 2
                idleTimeout      = 600_000          // 10 min
                connectionTimeout= 30_000           // 30 s
                maxLifetime      = 1_800_000        // 30 min
                driverClassName  = "org.postgresql.Driver"

                connectionTestQuery = "SELECT 1"
            }
            return HikariDataSource(config)
        }
    }

    override suspend fun ingestCpu(
        deviceId: String,
        ts:       Instant,
        metric:   CpuMetric
    ) = newSuspendedTransaction(Dispatchers.IO, database) {
        touchDevice(deviceId, ts)

        MetricCpuTable.insert {
            it[MetricCpuTable.deviceId]    = deviceId
            it[MetricCpuTable.ts]          = ts
            it[MetricCpuTable.usagePercent] = metric.usagePercent
            it[MetricCpuTable.loadAvg1m]   = metric.loadAvg1m
            it[MetricCpuTable.loadAvg5m]   = metric.loadAvg5m
            it[MetricCpuTable.loadAvg15m]  = metric.loadAvg15m
        }

        // GET /api/devices can return current values without a JOIN.
        DevicesTable.update({ DevicesTable.id eq deviceId }) {
            it[cpuPercent] = metric.usagePercent.coerceIn(0.0, 100.0)
        }
    }

    override suspend fun ingestMemory(
        deviceId: String,
        ts:       Instant,
        metric:   MemoryMetric
    ) = newSuspendedTransaction(Dispatchers.IO, database) {
        touchDevice(deviceId, ts)

        MetricMemoryTable.insert {
            it[MetricMemoryTable.deviceId]    = deviceId
            it[MetricMemoryTable.ts]          = ts
            it[MetricMemoryTable.totalKb]     = metric.totalKb
            it[MetricMemoryTable.availableKb] = metric.availableKb
            it[MetricMemoryTable.usedKb]      = metric.usedKb
            it[MetricMemoryTable.usagePercent] = metric.usagePercent
            it[MetricMemoryTable.swapTotalKb] = metric.swapTotalKb ?: 0L
            it[MetricMemoryTable.swapFreeKb]  = metric.swapFreeKb  ?: 0L
            it[MetricMemoryTable.swapUsagePct] = metric.swapUsagePercent ?: 0.0
        }

        DevicesTable.update({ DevicesTable.id eq deviceId }) {
            it[memoryPercent] = metric.usagePercent.coerceIn(0.0, 100.0)
        }
    }

    override suspend fun ingestNetwork(
        deviceId: String,
        ts:       Instant,
        iface:    NetworkInterface
    ) = newSuspendedTransaction(Dispatchers.IO, database) {
        touchDevice(deviceId, ts)

        MetricNetworkTable.insert {
            it[MetricNetworkTable.deviceId]      = deviceId
            it[MetricNetworkTable.ts]            = ts
            it[MetricNetworkTable.interfaceName] = iface.interfaceName
            it[MetricNetworkTable.rxBytesPerSec] = iface.rxBytesPerSec
            it[MetricNetworkTable.txBytesPerSec] = iface.txBytesPerSec
            it[MetricNetworkTable.rxErrors]      = iface.rxErrors      ?: 0L
            it[MetricNetworkTable.txErrors]      = iface.txErrors      ?: 0L
            it[MetricNetworkTable.rxDropped]     = iface.rxDropped     ?: 0L
            it[MetricNetworkTable.txDropped]     = iface.txDropped     ?: 0L
        }

        val maxRxMbps = iface.rxBytesPerSec.toDouble() / (1024 * 1024)
        DevicesTable.update({ DevicesTable.id eq deviceId }) {
            it[networkRxMbps] = maxRxMbps
        }
    }

    override suspend fun getAllDevices(): List<Device> =
        newSuspendedTransaction(Dispatchers.IO, database) {
            DevicesTable.selectAll()
                .orderBy(DevicesTable.lastSeen to SortOrder.DESC_NULLS_LAST)
                .map { row ->
                    Device(
                        id            = row[DevicesTable.id],
                        name          = row[DevicesTable.name],
                        status        = row[DevicesTable.status].toDeviceStatus(),
                        lastSeen      = row[DevicesTable.lastSeen]?.toString() ?: "",
                        cpuPercent    = row[DevicesTable.cpuPercent]    ?: 0.0,
                        memoryPercent = row[DevicesTable.memoryPercent] ?: 0.0,
                        networkRxMbps = row[DevicesTable.networkRxMbps] ?: 0.0
                    )
                }
        }

    override suspend fun getDevice(deviceId: String): DeviceDetails? =
        newSuspendedTransaction(Dispatchers.IO, database) {
            DevicesTable.selectAll()
                .where { DevicesTable.id eq deviceId }
                .firstOrNull()
                ?.let { row ->
                    DeviceDetails(
                        id       = row[DevicesTable.id],
                        name     = row[DevicesTable.name],
                        status   = row[DevicesTable.status].toDeviceStatus(),
                        lastSeen = row[DevicesTable.lastSeen]?.toString() ?: "",
                        uptime   = row[DevicesTable.uptimeSeconds],
                        platform = row[DevicesTable.platform],
                        cpuCores = row[DevicesTable.cpuCores]
                    )
                }
        }

    override suspend fun getLatestMetrics(deviceId: String): LatestMetrics? =
        newSuspendedTransaction(Dispatchers.IO, database) {

            // Latest CPU sample
            val cpuRow = MetricCpuTable.selectAll()
                .where  { MetricCpuTable.deviceId eq deviceId }
                .orderBy(MetricCpuTable.ts to SortOrder.DESC)
                .limit(1)
                .firstOrNull() ?: return@newSuspendedTransaction null

            val cpu = CpuMetric(
                usagePercent = cpuRow[MetricCpuTable.usagePercent],
                loadAvg1m    = cpuRow[MetricCpuTable.loadAvg1m],
                loadAvg5m    = cpuRow[MetricCpuTable.loadAvg5m],
                loadAvg15m   = cpuRow[MetricCpuTable.loadAvg15m]
            )
            val snapshotTs = cpuRow[MetricCpuTable.ts].toEpochMilliseconds()

            // Latest memory sample
            val memory = MetricMemoryTable.selectAll()
                .where  { MetricMemoryTable.deviceId eq deviceId }
                .orderBy(MetricMemoryTable.ts to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let { row ->
                    MemoryMetric(
                        totalKb          = row[MetricMemoryTable.totalKb],
                        availableKb      = row[MetricMemoryTable.availableKb],
                        usedKb           = row[MetricMemoryTable.usedKb],
                        usagePercent     = row[MetricMemoryTable.usagePercent],
                        swapTotalKb      = row[MetricMemoryTable.swapTotalKb],
                        swapFreeKb       = row[MetricMemoryTable.swapFreeKb],
                        swapUsagePercent = row[MetricMemoryTable.swapUsagePct]
                    )
                } ?: return@newSuspendedTransaction null

            val network = MetricNetworkTable.selectAll()
                .where  { MetricNetworkTable.deviceId eq deviceId }
                .orderBy(MetricNetworkTable.ts to SortOrder.DESC)
                .limit(200)
                .distinctBy   { it[MetricNetworkTable.interfaceName] }
                .map { row ->
                    NetworkInterface(
                        interfaceName = row[MetricNetworkTable.interfaceName],
                        rxBytesPerSec = row[MetricNetworkTable.rxBytesPerSec],
                        txBytesPerSec = row[MetricNetworkTable.txBytesPerSec],
                        rxErrors      = row[MetricNetworkTable.rxErrors],
                        txErrors      = row[MetricNetworkTable.txErrors],
                        rxDropped     = row[MetricNetworkTable.rxDropped],
                        txDropped     = row[MetricNetworkTable.txDropped]
                    )
                }

            LatestMetrics(
                cpu       = cpu,
                memory    = memory,
                network   = network,
                timestamp = snapshotTs
            )
        }

    override suspend fun getMetricsHistory(
        deviceId: String,
        from:     Instant,
        to:       Instant,
        type:     String
    ): List<MetricDataPoint> = newSuspendedTransaction(Dispatchers.IO, database) {
        when (type.lowercase()) {

            "cpu" -> MetricCpuTable.selectAll()
                .where {
                    (MetricCpuTable.deviceId eq deviceId) and
                    (MetricCpuTable.ts greaterEq from) and
                    (MetricCpuTable.ts lessEq to)
                }
                .orderBy(MetricCpuTable.ts to SortOrder.ASC)
                .map { row ->
                    MetricDataPoint(
                        timestamp = row[MetricCpuTable.ts].toEpochMilliseconds(),
                        value     = row[MetricCpuTable.usagePercent]
                    )
                }

            "memory" -> MetricMemoryTable.selectAll()
                .where {
                    (MetricMemoryTable.deviceId eq deviceId) and
                    (MetricMemoryTable.ts greaterEq from) and
                    (MetricMemoryTable.ts lessEq to)
                }
                .orderBy(MetricMemoryTable.ts to SortOrder.ASC)
                .map { row ->
                    MetricDataPoint(
                        timestamp = row[MetricMemoryTable.ts].toEpochMilliseconds(),
                        value     = row[MetricMemoryTable.usagePercent]
                    )
                }

            "network" -> {
                MetricNetworkTable.selectAll()
                    .where {
                        (MetricNetworkTable.deviceId eq deviceId) and
                        (MetricNetworkTable.ts greaterEq from) and
                        (MetricNetworkTable.ts lessEq to)
                    }
                    .orderBy(MetricNetworkTable.ts to SortOrder.ASC)
                    .groupBy { it[MetricNetworkTable.ts] }
                    .map { (ts, rows) ->
                        MetricDataPoint(
                            timestamp = ts.toEpochMilliseconds(),
                            value     = rows.maxOf { it[MetricNetworkTable.rxBytesPerSec] }
                                            .toDouble() / (1024 * 1024)
                        )
                    }
            }

            else -> emptyList()
        }
    }

    private fun touchDevice(deviceId: String, ts: Instant) {
        DevicesTable.upsert(
            onUpdateExclude = listOf(
                DevicesTable.name,
                DevicesTable.platform,
                DevicesTable.cpuCores,
                DevicesTable.uptimeSeconds,
                DevicesTable.cpuPercent,
                DevicesTable.memoryPercent,
                DevicesTable.networkRxMbps
            )
        ) {
            it[id]            = deviceId
            it[name]          = deviceId   
            it[status]        = "online"
            it[lastSeen]      = ts
            it[platform]      = "Linux"
            it[cpuCores]      = 0
            it[uptimeSeconds] = 0L
            it[cpuPercent]    = 0.0
            it[memoryPercent] = 0.0
            it[networkRxMbps] = 0.0
        }
    }
}

private fun String.toDeviceStatus(): DeviceStatus =
    if (this == "online") DeviceStatus.ONLINE else DeviceStatus.OFFLINE
