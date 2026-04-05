package app.edge_telemetry

import app.edge_telemetry.ai.AiEngineClient
import app.edge_telemetry.ai.AnomalyScheduler
import app.edge_telemetry.config.AppConfig
import app.edge_telemetry.grpc.TelemetryGrpcService
import app.edge_telemetry.models.*
import app.edge_telemetry.routes.deviceRoutes
import app.edge_telemetry.storage.InMemoryRepository
import app.edge_telemetry.storage.PostgresRepository
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

private val log = LoggerFactory.getLogger("app.edge_telemetry.Application")

fun main() {
    val repository: TelemetryRepository = buildRepository()

    val grpcServer = ServerBuilder
        .forPort(AppConfig.grpcPort)
        .addService(TelemetryGrpcService(repository))
        .build()

    grpcServer.start()
    log.info("gRPC server started on port {}", AppConfig.grpcPort)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down gRPC server")
        grpcServer.shutdown()
        grpcServer.awaitTermination()
    })

    embeddedServer(Netty, port = AppConfig.httpPort, host = "0.0.0.0") {
        module(repository)
    }.start(wait = true)
}

private fun buildRepository(): TelemetryRepository {
    val url = AppConfig.databaseUrl
    return if (url == null) {
        log.info("DATABASE_URL not set — using in-memory repository (no persistence)")
        InMemoryRepository()
    } else {
        log.info("DATABASE_URL set — connecting to PostgreSQL at {}", url)
        PostgresRepository.create(
            url      = url,
            user     = AppConfig.databaseUser,
            password = AppConfig.databasePassword,
            poolSize = AppConfig.databasePoolSize
        ).also {
            log.info("PostgreSQL connection pool ready (size={})", AppConfig.databasePoolSize)
        }
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

    val aiEngineUrl = AppConfig.aiEngineUrl
    if (aiEngineUrl != null) {
        log.info("AI_ENGINE_URL set — starting anomaly scheduler ({})", aiEngineUrl)

        val aiClient  = AiEngineClient(aiEngineUrl)
        val scheduler = AnomalyScheduler(
            repository      = repository,
            aiClient        = aiClient,
            intervalSeconds = AppConfig.anomalyIntervalSeconds,
            windowMinutes   = AppConfig.anomalyWindowMinutes
        )

        scheduler.start()

        environment.monitor.subscribe(ApplicationStopped) {
            log.info("ApplicationStopped — shutting down anomaly scheduler")
            scheduler.stop()
            aiClient.close()
        }
    } else {
        log.info("AI_ENGINE_URL not set — anomaly scheduler disabled")
    }

    routing {
        get("/") {
            call.respond(ApiInfoResponse(
                name    = "Edge Telemetry Backend",
                version = "0.4.0",
                endpoints = mapOf(
                    "devices" to "/api/devices",
                    "health"  to "/api/health",
                    "grpc"    to "port ${AppConfig.grpcPort} (TelemetryService.StreamMetrics)"
                )
            ))
        }
        deviceRoutes(repository)
    }

    environment.log.info("Ktor HTTP server started on port {}", AppConfig.httpPort)
}
