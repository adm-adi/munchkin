package com.munchkin.app.network

import com.munchkin.app.BuildConfig

/**
 * Active backend endpoint.
 *
 * Values come from BuildConfig so the target can be overridden at build time
 * without editing source — see the `munchkinScheme`/`munchkinHost`/`munchkinPort`
 * Gradle properties in app/build.gradle.kts. The defaults are production.
 */
object ServerConfig {
    val SCHEME: String = BuildConfig.SERVER_SCHEME
    val HOST: String = BuildConfig.SERVER_HOST
    val PORT: Int = BuildConfig.SERVER_PORT

    val WS_URL: String = "$SCHEME://$HOST:$PORT"

    val HTTP_SCHEME: String = if (SCHEME.equals("wss", ignoreCase = true)) "https" else "http"

    val WEB_ORIGIN: String = when {
        HTTP_SCHEME == "https" && PORT == 443 -> "$HTTP_SCHEME://$HOST"
        HTTP_SCHEME == "http" && PORT == 80 -> "$HTTP_SCHEME://$HOST"
        else -> "$HTTP_SCHEME://$HOST:$PORT"
    }

    fun inviteUrl(joinCode: String): String = "$WEB_ORIGIN/#/join/$joinCode"
}
