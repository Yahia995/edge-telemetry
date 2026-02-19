package app.edge_telemetry

import app.edge_telemetry.grpc.TelemetryGrpcService
import app.edge_telemetry.models.*
import app.edge_telemetry.routes.deviceRoutes
import app.edge_telemetry.storage.DeviceRegistry
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

// gRPC and Ktor run on separate ports:
//
//   :8080  — Ktor HTTP/REST  (dashboard, health checks)
//   :50051 — gRPC            (agent telemetry stream)
//
// They share the same DeviceRegistry instance so agent data is immediately
// visible through the REST API without any inter-process communication.

private const val HTTP_PORT  = 8080
private const val GRPC_PORT  = 50051

private val log = LoggerFactory.getLogger("app.edge_telemetry.Application")

fun main() {
    val registry = DeviceRegistry()

    // ── gRPC server ──────────────────────────────────────────────────────
    //
    // ServerBuilder.forPort creates a plain-text (no TLS) gRPC server.
    // TLS termination belongs at the load balancer in production; for the
    // Projet SI environment (local / Podman) plain-text is appropriate.
    val grpcServer = ServerBuilder
        .forPort(GRPC_PORT)
        .addService(TelemetryGrpcService(registry))
        .build()

    grpcServer.start()
    log.info("gRPC server started on port {}", GRPC_PORT)

    // Ensure the gRPC server shuts down cleanly when the JVM exits.
    // This fires on SIGINT / SIGTERM, mirroring the agent's graceful shutdown.
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down gRPC server")
        grpcServer.shutdown()
        grpcServer.awaitTermination()
    })

    // ── Ktor HTTP server ─────────────────────────────────────────────────
    //
    // embeddedServer blocks until the server stops, so it must come after
    // the gRPC server is already started. Both servers share the same JVM
    // thread pool managed by Ktor's Netty engine.
    embeddedServer(Netty, port = HTTP_PORT, host = "0.0.0.0") {
        module(registry)
    }.start(wait = true)
}

fun Application.module(registry: DeviceRegistry) {

    install(ContentNegotiation) {
        json(Json {
            prettyPrint        = true
            isLenient          = true
            ignoreUnknownKeys  = true
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

        deviceRoutes(registry)
    }

    environment.log.info("Ktor HTTP server started on port {}", HTTP_PORT)
}
