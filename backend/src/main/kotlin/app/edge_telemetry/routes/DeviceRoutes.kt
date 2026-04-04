package app.edge_telemetry.routes

import app.edge_telemetry.models.*
import app.edge_telemetry.storage.TelemetryRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Instant

fun Route.deviceRoutes(repository: TelemetryRepository) {
    route("/api") {
        // GET /api/devices
        get("/devices") {
            val devices = repository.getAllDevices()
            call.respond(DevicesResponse(devices))
        }

        // GET /api/devices/:deviceId
        get("/devices/{deviceId}") {
            val deviceId = call.parameters["deviceId"]
            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Missing deviceId"))
                return@get
            }

            val device = repository.getDevice(deviceId)
            if (device == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Device not found"))
            } else {
                call.respond(device)
            }
        }

        // GET /api/devices/:deviceId/metrics/latest
        get("/devices/{deviceId}/metrics/latest") {
            val deviceId = call.parameters["deviceId"]
            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Missing deviceId"))
                return@get
            }

            val metrics = repository.getLatestMetrics(deviceId)
            if (metrics == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(error = "Device not found or no metrics available")
                )
            } else {
                call.respond(metrics)
            }
        }

        // GET /api/devices/:deviceId/metrics?from=<ms>&to=<ms>&type=<cpu|memory|network>
        get("/devices/{deviceId}/metrics") {
            val deviceId = call.parameters["deviceId"]
            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Missing deviceId"))
                return@get
            }

            val fromMs = call.request.queryParameters["from"]?.toLongOrNull()
            if (fromMs == null) {
                call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(error = "Missing or invalid 'from' parameter"))
                return@get
            }

            val toMs = call.request.queryParameters["to"]?.toLongOrNull()
            if (toMs == null) {
                call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(error = "Missing or invalid 'to' parameter"))
                return@get
            }

            val type = call.request.queryParameters["type"] ?: "cpu"
            if (type !in listOf("cpu", "memory", "network")) {
                call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(error = "Invalid type. Must be one of: cpu, memory, network"))
                return@get
            }

            val from = Instant.fromEpochMilliseconds(fromMs)
            val to   = Instant.fromEpochMilliseconds(toMs)

            val metrics = repository.getMetricsHistory(deviceId, from, to, type)
            call.respond(MetricsHistoryResponse(deviceId = deviceId, type = type, metrics = metrics))
        }

        // Health check
        get("/health") {
            call.respond(HealthResponse(
                status    = "healthy",
                timestamp = System.currentTimeMillis()
            ))
        }
    }
}
