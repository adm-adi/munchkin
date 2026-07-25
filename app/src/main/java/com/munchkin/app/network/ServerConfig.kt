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

    /** True when talking to the server over an unencrypted socket. */
    val isInsecure: Boolean get() = SCHEME != "wss"
}
