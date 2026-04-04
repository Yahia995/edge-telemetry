package app.edge_telemetry

import app.edge_telemetry.grpc.TelemetryGrpcService
import app.edge_telemetry.models.*
import app.edge_telemetry.routes.deviceRoutes
import app.edge_telemetry.storage.InMemoryRepository
import app.edge_telemetry.storage.TelemetryRepository
import io.grpc.ServerBuilder
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

// Two servers, one JVM process, one shared repository:
//
//   :8080  — Ktor HTTP/REST  (dashboard, health checks)
//   :50051 — gRPC            (agent telemetry stream)
//
// Both servers receive the same [TelemetryRepository] instance.
// Metrics written by the gRPC server are immediately visible through
// the REST API — no IPC, no serialization between transports.
//
// Repository selection (Phase 3 step 6 will extend this):
//   - DATABASE_URL absent → InMemoryRepository  (no external dependency)
//   - DATABASE_URL present → PostgresRepository (to be implemented)

private const val HTTP_PORT = 8080
private const val GRPC_PORT = 50051

private val log = LoggerFactory.getLogger("app.edge_telemetry.Application")

fun main() {
    val repository: TelemetryRepository = buildRepository()

    // gRPC server
    val grpcServer = ServerBuilder
        .forPort(GRPC_PORT)
        .addService(TelemetryGrpcService(repository))
        .build()

    grpcServer.start()
    log.info("gRPC server started on port {}", GRPC_PORT)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down gRPC server")
        grpcServer.shutdown()
        grpcServer.awaitTermination()
    })

    // Ktor HTTP server
    embeddedServer(Netty, port = HTTP_PORT, host = "0.0.0.0") {
        module(repository)
    }.start(wait = true)
}

/**
 * Selects the repository implementation based on environment.
 *
 * DATABASE_URL absent  → [InMemoryRepository]:  works out of the box,
 *                        no Podman required. Suitable for agent dev work.
 * DATABASE_URL present → PostgresRepository:     persistent storage.
 *                        Introduced in Phase 3 step 6.
 *
 * This function is the single place where the implementation choice is
 * made. TelemetryGrpcService and DeviceRoutes depend only on the interface.
 */
private fun buildRepository(): TelemetryRepository {
    val dbUrl = System.getenv("DATABASE_URL")
    return if (dbUrl.isNullOrBlank()) {
        log.info("DATABASE_URL not set — using in-memory repository (no persistence)")
        InMemoryRepository()
    } else {
        // PostgresRepository will be wired here in Phase 3 step 6.
        // For now, fall back to in-memory so the service stays runnable.
        log.warn("DATABASE_URL is set but PostgresRepository is not yet implemented — " +
                 "falling back to in-memory repository")
        InMemoryRepository()
    }
}

fun Application.module(repository: TelemetryRepository) {

    install(ContentNegotiation) {
        json(Json {
            prettyPrint       = true
            isLenient         = true
            ignoreUnknownKeys = true
        })
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        anyHost()
    }

    install(CallLogging) {
        level  = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(error = "Internal server error", message = cause.message)
            )
        }
    }

    routing {
        get("/") {
            call.respond(ApiInfoResponse(
                name    = "Edge Telemetry Backend",
                version = "0.2.0",
                endpoints = mapOf(
                    "devices" to "/api/devices",
                    "health"  to "/api/health",
                    "grpc"    to "port $GRPC_PORT (TelemetryService.StreamMetrics)"
                )
            ))
        }
        deviceRoutes(repository)
    }

    environment.log.info("Ktor HTTP server started on port {}", HTTP_PORT)
}
