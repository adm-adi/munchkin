package com.munchkin.app.data

import com.munchkin.app.network.ApiClient
import com.munchkin.app.network.AuthHttpResponse
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.network.DiscoveredGame
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.network.HostedGame
import com.munchkin.app.network.LeaderboardEntry
import com.munchkin.app.network.UserProfile
import java.io.Closeable

interface AuthDataSource {
    suspend fun restore(token: String): Result<AuthHttpResponse>
    suspend fun register(username: String, email: String, password: String): Result<AuthHttpResponse>
    suspend fun login(email: String, password: String): Result<AuthHttpResponse>
    fun logout()
}

class AuthRepository(
    private val apiClient: ApiClient,
    private val sessionManager: AccountSessionStore,
    private val serverUrl: String
) : AuthDataSource {
    override suspend fun restore(token: String): Result<AuthHttpResponse> {
        return apiClient.validateToken(serverUrl, token).onSuccess(::persist)
    }

    override suspend fun register(username: String, email: String, password: String): Result<AuthHttpResponse> {
        return apiClient.register(serverUrl, username, email, password).onSuccess(::persist)
    }

    override suspend fun login(email: String, password: String): Result<AuthHttpResponse> {
        return apiClient.login(serverUrl, email, password).onSuccess(::persist)
    }

    override fun logout() {
        sessionManager.clearSession()
    }

    private fun persist(auth: AuthHttpResponse) {
        sessionManager.saveSession(auth.user)
        sessionManager.saveAuthToken(auth.token)
    }
}

interface ProfileDataSource {
    suspend fun updateProfile(username: String?, password: String?): Result<UserProfile>
}

class ProfileRepository(
    private val apiClient: ApiClient,
    private val sessionManager: AccountSessionStore,
    private val serverUrl: String
) : ProfileDataSource {
    override suspend fun updateProfile(username: String?, password: String?): Result<UserProfile> {
        val token = sessionManager.getAuthToken()
            ?: return Result.failure(Exception("Session expired"))
        return apiClient.updateProfile(serverUrl, token, username, password).onSuccess { updated ->
            sessionManager.saveSession(updated)
        }
    }
}

interface CatalogDataSource {
    suspend fun searchMonsters(query: String): Result<List<CatalogMonster>>
    suspend fun addMonster(monster: CatalogMonster): Result<CatalogMonster>
}

class CatalogRepository(
    private val apiClient: ApiClient,
    private val sessionManager: SessionManager,
    private val serverUrl: String
) : CatalogDataSource, Closeable {
    override suspend fun searchMonsters(query: String): Result<List<CatalogMonster>> {
        return apiClient.searchMonsters(serverUrl, query)
    }

    override suspend fun addMonster(monster: CatalogMonster): Result<CatalogMonster> {
        val token = sessionManager.getAuthToken()
            ?: return Result.failure(Exception("Inicia sesi\u00f3n para guardar monstruos"))
        return apiClient.addMonster(serverUrl, token, monster)
    }

    override fun close() {
        apiClient.close()
    }
}

interface HistoryDataSource {
    suspend fun loadHistory(): Result<List<GameHistoryItem>>
    suspend fun loadLeaderboard(): Result<List<LeaderboardEntry>>
}

class HistoryRepository(
    private val apiClient: ApiClient,
    private val sessionManager: SessionManager,
    private val serverUrl: String
) : HistoryDataSource, Closeable {
    override suspend fun loadHistory(): Result<List<GameHistoryItem>> {
        val token = sessionManager.getAuthToken()
            ?: return Result.failure(Exception("Session expired"))
        return apiClient.getHistory(serverUrl, token)
    }

    override suspend fun loadLeaderboard(): Result<List<LeaderboardEntry>> {
        return apiClient.getLeaderboard(serverUrl)
    }

    override fun close() {
        apiClient.close()
    }
}

interface GameDirectoryDataSource {
    suspend fun discoverGames(): Result<List<DiscoveredGame>>
    suspend fun getHostedGames(): Result<List<HostedGame>>
    suspend fun deleteHostedGame(gameId: String): Result<Unit>
}

class HostedGamesRepository(
    private val apiClient: ApiClient,
    private val sessionManager: SessionManager,
    private val serverUrl: String
) : GameDirectoryDataSource, Closeable {
    override suspend fun discoverGames(): Result<List<DiscoveredGame>> {
        return apiClient.getOpenGames(serverUrl)
    }

    override suspend fun getHostedGames(): Result<List<HostedGame>> {
        val token = sessionManager.getAuthToken()
            ?: return Result.failure(Exception("Session expired"))
        return apiClient.getHostedGames(serverUrl, token)
    }

    override suspend fun deleteHostedGame(gameId: String): Result<Unit> {
        val token = sessionManager.getAuthToken()
            ?: return Result.failure(Exception("Session expired"))
        return apiClient.deleteHostedGame(serverUrl, token, gameId)
    }

    override fun close() {
        apiClient.close()
    }
}
