package com.munchkin.app.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val JOIN_CODE_REGEX = Regex("^[A-Z0-9]{6,8}$")
private val JOIN_PATH_REGEX = Regex("/join/([^/?#&\\s]+)", RegexOption.IGNORE_CASE)
private val JOIN_QUERY_REGEX = Regex("(?:^|[?&])join=([^&#\\s]+)", RegexOption.IGNORE_CASE)

/**
 * Extracts and validates a 6-8 character alphanumeric Munchkin room join code
 * from raw scanner or text input.
 *
 * Supported formats:
 * - Plain code: `ABC123`
 * - Fragment/path URL: `https://example.com/#/join/ABC123`, `https://example.com/join/ABC123`, `/join/ABC123`
 * - Query URL: `https://example.com/?join=ABC123`
 * - Legacy QR JSON: `{"joinCode":"ABC123","wsUrl":"..."}` (any `wsUrl` field is ignored)
 *
 * Returns the uppercased join code if valid (`^[A-Z0-9]{6,8}$`), or `null` otherwise.
 */
fun extractJoinCode(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val candidate = when {
        trimmed.startsWith("{") -> extractFromJson(trimmed)
        trimmed.contains("/join/", ignoreCase = true) ->
            JOIN_PATH_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
        trimmed.contains("join=", ignoreCase = true) ->
            JOIN_QUERY_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
        else -> trimmed
    } ?: return null

    val normalized = candidate.trim().uppercase()
    return normalized.takeIf { JOIN_CODE_REGEX.matches(it) }
}

private fun extractFromJson(rawJson: String): String? {
    return try {
        val obj = Json.parseToJsonElement(rawJson).jsonObject
        // Deliberately ignore any legacy `wsUrl` field to prevent untrusted server redirection.
        val joinCodeElement = obj["joinCode"] as? JsonPrimitive ?: return null
        if (!joinCodeElement.isString) return null
        joinCodeElement.content
    } catch (_: Exception) {
        null
    }
}

object JoinCodeParser {
    fun extractJoinCode(raw: String): String? = com.munchkin.app.network.extractJoinCode(raw)
}
