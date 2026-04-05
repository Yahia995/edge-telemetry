package app.edge_telemetry.ai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AiEngineClient(private val baseUrl: String) {

    private val log = LoggerFactory.getLogger(AiEngineClient::class.java)

    private val client = HttpClient(CIO) {

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient         = true
            })
        }

        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) =
                    log.debug("ktor-client: {}", message)
            }
            level = LogLevel.NONE   
        }

        install(HttpTimeout) {
            requestTimeoutMillis  = 10_000
            connectTimeoutMillis  =  5_000
            socketTimeoutMillis   = 10_000
        }

        expectSuccess = false
    }

    suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse? {
        return try {
            val response = client.post("$baseUrl/analyze") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.isSuccess()) {
                response.body<AnalyzeResponse>()
            } else {
                log.warn(
                    "AI engine returned {} for device={}",
                    response.status, request.deviceId
                )
                null
            }
        } catch (e: Exception) {
            log.warn(
                "AI engine unreachable for device={}: {}",
                request.deviceId, e.message
            )
            null
        }
    }

    suspend fun isHealthy(): Boolean {
        return try {
            val response = client.get("$baseUrl/health")
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        client.close()
    }
}
