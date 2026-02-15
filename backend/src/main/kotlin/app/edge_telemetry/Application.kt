package app.edge_telemetry

import app.edge_telemetry.models.*
import app.edge_telemetry.routes.deviceRoutes
import app.edge_telemetry.storage.DeviceRegistry
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.request.path
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Initialize device registry
    val deviceRegistry = DeviceRegistry()
    
    // Configure JSON serialization
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
    
    // Configure CORS for React dev server
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
        
        // Allow React dev server
        anyHost()
    }
    
    // Configure logging
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }
    
    // Configure error handling
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "Internal server error",
                    message = cause.message
                )
            )
        }
    }   
    
    // Configure routing
    routing {
        // Root endpoint
        get("/") {
            call.respond(
                ApiInfoResponse(
                    name = "Edge Telemetry Backend",
                    version = "0.2.0",
                    endpoints = mapOf(
                        "devices" to "/api/devices",
                        "health" to "/api/health"
                    )
                )
            )
        }

        // Device routes
        deviceRoutes(deviceRegistry)
    }

    environment.log.info("Edge Telemetry Backend started on port 8080")
}
