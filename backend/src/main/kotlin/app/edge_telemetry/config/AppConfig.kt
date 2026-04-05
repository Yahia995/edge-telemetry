package app.edge_telemetry.config

object AppConfig {

    val httpPort: Int = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 8080

    val grpcPort: Int = System.getenv("GRPC_PORT")?.toIntOrNull() ?: 50051

    val databaseUrl: String? = System.getenv("DATABASE_URL")
        ?.takeIf { it.isNotBlank() }

    val databaseUser: String = System.getenv("DATABASE_USER") ?: "telemetry"

    val databasePassword: String = System.getenv("DATABASE_PASSWORD") ?: "telemetry"

    val databasePoolSize: Int = System.getenv("DATABASE_POOL_SIZE")?.toIntOrNull() ?: 10

    val aiEngineUrl: String? = System.getenv("AI_ENGINE_URL")
        ?.takeIf { it.isNotBlank() }

    val anomalyIntervalSeconds: Long =
        System.getenv("ANOMALY_INTERVAL_SECONDS")?.toLongOrNull() ?: 60L

    val anomalyWindowMinutes: Int =
        System.getenv("ANOMALY_WINDOW_MINUTES")?.toIntOrNull() ?: 60
}
