package com.munchkin.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ApiClient {
    private val client = HttpClient(CIO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    suspend fun register(
        serverUrl: String,
        username: String,
        email: String,
        password: String,
        avatarId: Int = 0
    ): Result<AuthHttpResponse> {
        return request(
            serverUrl = serverUrl,
            path = "/api/auth/register",
            method = HttpVerb.POST,
            body = json.encodeToString(
                RegisterHttpRequest(
                    username = username,
                    email = email,
                    password = password,
                    avatarId = avatarId
                )
            )
        )
    }

    suspend fun login(
        serverUrl: String,
        email: String,
        password: String
    ): Result<AuthHttpResponse> {
        return request(
            serverUrl = serverUrl,
            path = "/api/auth/login",
            method = HttpVerb.POST,
            body = json.encodeToString(
                LoginHttpRequest(
                    email = email,
                    password = password
                )
            )
        )
    }

    suspend fun validateToken(
        serverUrl: String,
        token: String
    ): Result<AuthHttpResponse> {
        return request<UserProfile>(
            serverUrl = serverUrl,
            path = "/api/profile",
            method = HttpVerb.GET,
            token = token
        ).map { user ->
            AuthHttpResponse(user = user, token = token)
        }
    }

    suspend fun updateProfile(
        serverUrl: String,
        token: String,
        username: String?,
        password: String?
    ): Result<UserProfile> {
        return request(
            serverUrl = serverUrl,
            path = "/api/profile",
            method = HttpVerb.PATCH,
            token = token,
            body = json.encodeToString(
                UpdateProfileHttpRequest(
                    username = username,
                    password = password
                )
            )
        )
    }

    suspend fun getOpenGames(serverUrl: String): Result<List<DiscoveredGame>> {
        return request<OpenGamesResponse>(
            serverUrl = serverUrl,
            path = "/api/games/open",
            method = HttpVerb.GET
        ).map { response ->
            response.games.map { game ->
                DiscoveredGame(
                    hostName = game.hostName,
                    joinCode = game.joinCode,
                    playerCount = game.playerCount,
                    maxPlayers = game.maxPlayers,
                    wsUrl = serverUrl
                )
            }
        }
    }

    suspend fun getHostedGames(
        serverUrl: String,
        token: String
    ): Result<List<HostedGame>> {
        return request(
            serverUrl = serverUrl,
            path = "/api/games/hosted",
            method = HttpVerb.GET,
            token = token
        )
    }

    suspend fun deleteHostedGame(
        serverUrl: String,
        token: String,
        gameId: String
    ): Result<Unit> {
        return rawRequest(
            serverUrl = serverUrl,
            path = "/api/games/hosted/$gameId",
            method = HttpVerb.DELETE,
            token = token
        ).map { }
    }

    suspend fun getHistory(
        serverUrl: String,
        token: String
    ): Result<List<GameHistoryItem>> {
        return request(
            serverUrl = serverUrl,
            path = "/api/history",
            method = HttpVerb.GET,
            token = token
        )
    }

    suspend fun getLeaderboard(serverUrl: String): Result<List<LeaderboardEntry>> {
        return request(
            serverUrl = serverUrl,
            path = "/api/leaderboard",
            method = HttpVerb.GET
        )
    }

    suspend fun searchMonsters(
        serverUrl: String,
        query: String
    ): Result<List<CatalogMonster>> {
        return try {
            val response = client.get("${toHttpBase(serverUrl)}/api/catalog/monsters") {
                accept(ContentType.Application.Json)
                parameter("q", query)
            }
            decodeResponse(response.bodyAsText(), response.status.value).map { text ->
                json.decodeFromString(text)
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun addMonster(
        serverUrl: String,
        token: String,
        monster: CatalogMonster
    ): Result<CatalogMonster> {
        return request(
            serverUrl = serverUrl,
            path = "/api/catalog/monsters",
            method = HttpVerb.POST,
            token = token,
            body = json.encodeToString(
                CatalogAddHttpRequest(
                    monster = monster
                )
            )
        )
    }

    fun close() {
        client.close()
    }

    private suspend inline fun <reified T> request(
        serverUrl: String,
        path: String,
        method: HttpVerb,
        token: String? = null,
        body: String? = null
    ): Result<T> {
        return rawRequest(serverUrl, path, method, token, body).map { text ->
            json.decodeFromString<T>(text)
        }
    }

    private suspend fun rawRequest(
        serverUrl: String,
        path: String,
        method: HttpVerb,
        token: String? = null,
        body: String? = null
    ): Result<String> {
        return try {
            val baseUrl = toHttpBase(serverUrl)
            val response = when (method) {
                HttpVerb.GET -> client.get("$baseUrl$path") {
                    accept(ContentType.Application.Json)
                    token?.let { bearerAuth(it) }
                }

                HttpVerb.POST -> client.post("$baseUrl$path") {
                    accept(ContentType.Application.Json)
                    contentType(ContentType.Application.Json)
                    token?.let { bearerAuth(it) }
                    if (body != null) {
                        setBody(body)
                    }
                }

                HttpVerb.PATCH -> client.patch("$baseUrl$path") {
                    accept(ContentType.Application.Json)
                    contentType(ContentType.Application.Json)
                    token?.let { bearerAuth(it) }
                    if (body != null) {
                        setBody(body)
                    }
                }

                HttpVerb.DELETE -> client.delete("$baseUrl$path") {
                    accept(ContentType.Application.Json)
                    token?.let { bearerAuth(it) }
                }
            }

            decodeResponse(response.bodyAsText(), response.status.value)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun decodeResponse(body: String, statusCode: Int): Result<String> {
        if (statusCode in 200..299) {
            return Result.success(body)
        }
        return Result.failure(Exception(extractErrorMessage(body)))
    }

    private fun extractErrorMessage(body: String): String {
        return runCatching {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull() ?: body.ifBlank { "Request failed" }
    }

    private fun toHttpBase(serverUrl: String): String {
        val match = URL_REGEX.find(serverUrl)
            ?: throw IllegalArgumentException("Invalid server URL: $serverUrl")
        val scheme = if (match.groupValues[1] == "wss") "https" else "http"
        val host = match.groupValues[2]
        val port = match.groupValues[3]
        val portPart = port.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()
        return "$scheme://$host$portPart"
    }

    private enum class HttpVerb {
        GET,
        POST,
        PATCH,
        DELETE
    }

    private companion object {
        val URL_REGEX = Regex("""(wss?|https?)://([^:/]+)(?::(\d+))?.*""")
    }
}
