package app.edge_telemetry.grpc

import app.edge_telemetry.grpc.proto.Ack
import app.edge_telemetry.grpc.proto.Metric
import app.edge_telemetry.grpc.proto.TelemetryServiceGrpcKt
import app.edge_telemetry.storage.DeviceRegistry
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory

/**
 * Server-side implementation of the TelemetryService gRPC contract.
 *
 * The single RPC [streamMetrics] is client-streaming:
 *   - The agent opens one long-lived stream per connection.
 *   - It sends Metric messages at its configured interval (default 5 s).
 *   - The server replies with a single [Ack] when the stream closes
 *     (agent shutdown) or errors out.
 *
 * This class has no I/O of its own — it delegates all state mutations
 * to [DeviceRegistry.ingestMetric], keeping the gRPC layer thin.
 */
class TelemetryGrpcService(
    private val registry: DeviceRegistry
) : TelemetryServiceGrpcKt.TelemetryServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(TelemetryGrpcService::class.java)

    override suspend fun streamMetrics(requests: Flow<Metric>): Ack {
        var received = 0L
        var deviceId = "unknown"

        try {
            requests
                .onEach { metric ->
                    // Capture the device ID from the first message for logging.
                    if (received == 0L) {
                        deviceId = metric.deviceId
                        log.info("Agent connected: device_id={}", deviceId)
                    }

                    registry.ingestMetric(metric)
                    received++

                    // Log every 100th metric so the server isn't flooded at 5 s intervals
                    // but you still get periodic confirmation the stream is healthy.
                    if (received % 100 == 0L) {
                        log.debug("device_id={} metrics_received={}", deviceId, received)
                    }
                }
                .catch { cause ->
                    // This catches errors on the *receive* side of the flow (agent disconnect,
                    // parse error, network drop). We log and rethrow as a gRPC status so the
                    // agent sees a meaningful error code rather than UNKNOWN.
                    log.warn("Stream error for device_id={}: {}", deviceId, cause.message)
                    throw StatusException(
                        Status.UNAVAILABLE.withDescription(cause.message).withCause(cause)
                    )
                }
                .collect()

        } finally {
            // Executed on both clean close and error. This gives us a consistent
            // log line for "agent disconnected" regardless of the reason.
            log.info("Agent disconnected: device_id={} total_metrics={}", deviceId, received)
        }

        return Ack.newBuilder()
            .setSuccess(true)
            .setMessage("Stream closed cleanly")
            .setMetricsReceived(received)
            .build()
    }
}
