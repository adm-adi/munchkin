package com.munchkin.backend

data class BackendConfig(
    val port: Int = 8765,
    val databaseUrl: String = "jdbc:postgresql://localhost:5432/munchkin",
    val databaseUser: String = "munchkin",
    val databasePassword: String = "munchkin",
    val jwtSecret: String = "change-me-in-production",
    val jwtIssuer: String = "munchkin-backend",
    val jwtAudience: String = "munchkin-clients",
    val authRateLimitMaxRequests: Int = 20,
    val authRateLimitWindowMillis: Long = 60_000L,
    val webSocketMaxPayloadBytes: Int = 50 * 1024
) {
    companion object {
        fun fromEnvironment(): BackendConfig {
            return BackendConfig(
                port = System.getenv("MUNCHKIN_PORT")?.toIntOrNull() ?: 8765,
                databaseUrl = System.getenv("MUNCHKIN_DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/munchkin",
                databaseUser = System.getenv("MUNCHKIN_DATABASE_USER") ?: "munchkin",
                databasePassword = System.getenv("MUNCHKIN_DATABASE_PASSWORD") ?: "munchkin",
                jwtSecret = System.getenv("MUNCHKIN_JWT_SECRET") ?: "change-me-in-production",
                jwtIssuer = System.getenv("MUNCHKIN_JWT_ISSUER") ?: "munchkin-backend",
                jwtAudience = System.getenv("MUNCHKIN_JWT_AUDIENCE") ?: "munchkin-clients",
                authRateLimitMaxRequests = System.getenv("MUNCHKIN_AUTH_RATE_LIMIT_MAX")?.toIntOrNull() ?: 20,
                authRateLimitWindowMillis = System.getenv("MUNCHKIN_AUTH_RATE_LIMIT_WINDOW_MS")?.toLongOrNull() ?: 60_000L,
                webSocketMaxPayloadBytes = System.getenv("MUNCHKIN_WS_MAX_PAYLOAD_BYTES")?.toIntOrNull() ?: 50 * 1024
            )
        }
    }
}
