package com.munchkin.app.network

import java.net.URI

internal data class WsEndpoint(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String
) {
    val isSecure: Boolean get() = scheme == "wss"

    val urlString: String
        get() = "$scheme://${formatHost(host)}:$port$path"

    private fun formatHost(value: String): String {
        return if (":" in value && !value.startsWith("[")) "[$value]" else value
    }
}

internal object WsEndpointParser {
    private const val DEFAULT_PATH = "/ws/game"

    fun parse(rawUrl: String): WsEndpoint? {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        val scheme = when (uri.scheme?.lowercase()) {
            "ws", "http" -> "ws"
            "wss", "https" -> "wss"
            else -> return null
        }
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: defaultPort(scheme)
        val path = buildPath(uri)
        return WsEndpoint(scheme = scheme, host = host, port = port, path = path)
    }

    private fun defaultPort(scheme: String): Int {
        return if (scheme == "wss") 443 else 80
    }

    private fun buildPath(uri: URI): String {
        val basePath = uri.rawPath
            ?.takeIf { it.isNotBlank() && it != "/" }
            ?: DEFAULT_PATH
        val normalizedPath = if (basePath.startsWith("/")) basePath else "/$basePath"
        return uri.rawQuery?.let { "$normalizedPath?$it" } ?: normalizedPath
    }
}
