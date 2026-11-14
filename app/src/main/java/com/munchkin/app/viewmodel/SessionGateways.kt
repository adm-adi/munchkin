package com.munchkin.app.viewmodel

import com.munchkin.app.core.GameEvent
import com.munchkin.app.core.GameState
import com.munchkin.app.core.Gender
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerMeta
import com.munchkin.app.data.AppSettingsPrefs
import com.munchkin.app.data.GameRepository
import com.munchkin.app.data.SavedGame
import com.munchkin.app.data.SessionManager
import com.munchkin.app.network.AuthSuccessMessage
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.network.DiscoveredGame
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.HistoryResult
import com.munchkin.app.network.HostedGame
import com.munchkin.app.network.LeaderboardResult
import com.munchkin.app.network.ProfileUpdatedMessage
import com.munchkin.app.network.UserProfile
import com.munchkin.app.update.UpdateInfo
import com.munchkin.app.update.UpdateResult
import com.munchkin.app.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over [SessionManager] so ViewModels can be unit tested cleanly on JVM.
 */
internal interface SessionStore {
    fun getSession(): UserProfile?
    fun saveSession(user: UserProfile)
    fun getAuthToken(): String?
    fun saveAuthToken(token: String)
    fun savePlayerId(joinCode: String, playerId: String)
    fun getPlayerId(joinCode: String): String?
    fun saveReconnectToken(joinCode: String, token: String)
    fun getReconnectToken(joinCode: String): String?
    fun clearSession()
}

internal class SessionManagerStore(
    private val sessionManager: SessionManager
) : SessionStore {
    override fun getSession(): UserProfile? = sessionManager.getSession()
    override fun saveSession(user: UserProfile) = sessionManager.saveSession(user)
    override fun getAuthToken(): String? = sessionManager.getAuthToken()
    override fun saveAuthToken(token: String) = sessionManager.saveAuthToken(token)
    override fun savePlayerId(joinCode: String, playerId: String) =
        sessionManager.savePlayerId(joinCode, playerId)
    override fun getPlayerId(joinCode: String): String? = sessionManager.getPlayerId(joinCode)
    override fun saveReconnectToken(joinCode: String, token: String) =
        sessionManager.saveReconnectToken(joinCode, token)
    override fun getReconnectToken(joinCode: String): String? =
        sessionManager.getReconnectToken(joinCode)
    override fun clearSession() = sessionManager.clearSession()
}

/**
 * Abstraction over [GameRepository] for pure-JVM testing.
 */
internal interface SavedGameStore {
    fun getLatestSavedGame(): Flow<SavedGame?>
    suspend fun saveGame(gameState: GameState, myPlayerId: PlayerId, isHost: Boolean)
    suspend fun deleteAllSavedGames()
}

internal class RoomSavedGameStore(
    private val repository: GameRepository
) : SavedGameStore {
    override fun getLatestSavedGame(): Flow<SavedGame?> = repository.getLatestSavedGame()
    override suspend fun saveGame(gameState: GameState, myPlayerId: PlayerId, isHost: Boolean) =
        repository.saveGame(gameState, myPlayerId, isHost)
    override suspend fun deleteAllSavedGames() = repository.deleteAllSavedGames()
}

internal interface UpdateGateway {
    suspend fun checkForUpdate(): UpdateResult
    suspend fun downloadAndInstall(updateInfo: UpdateInfo): Result<Unit>
}

internal class AppUpdateGateway(
    private val checker: UpdateChecker
) : UpdateGateway {
    override suspend fun checkForUpdate(): UpdateResult = checker.checkForUpdate()
    override suspend fun downloadAndInstall(updateInfo: UpdateInfo): Result<Unit> =
        checker.downloadAndInstall(updateInfo)
}

internal interface LargeNumbersPrefs {
    val largeNumbersMode: StateFlow<Boolean>
    fun setLargeNumbersMode(enabled: Boolean)
}

internal class AppLargeNumbersPrefs(
    private val prefs: AppSettingsPrefs
) : LargeNumbersPrefs {
    override val largeNumbersMode: StateFlow<Boolean> = prefs.largeNumbersMode
    override fun setLargeNumbersMode(enabled: Boolean) = prefs.setLargeNumbersMode(enabled)
}

/**
 * Live WebSocket session connection abstraction wrapping [GameClient].
 */
internal interface SessionConnection {
    val rawClient: GameClient?
    val connectionState: StateFlow<ConnectionState>
    val gameState: StateFlow<GameState?>
    val myPlayerId: StateFlow<PlayerId?>
    val errors: SharedFlow<String>
    val reconnectAttempt: StateFlow<Int>
    val reconnectToken: StateFlow<String?>
    val gameDeleted: SharedFlow<String>
    val serverTimeOffsetMs: StateFlow<Long>

    fun nudge()
    suspend fun sendEvent(event: GameEvent): Result<Unit>
    suspend fun kickPlayer(targetPlayerId: PlayerId): Result<Unit>
    suspend fun sendTransferHost(targetPlayerId: PlayerId): Result<Unit>
    suspend fun sendDeleteGame(): Result<Unit>
    suspend fun sendSwapPlayers(player1: PlayerId, player2: PlayerId): Result<Unit>
    suspend fun sendGameOver(gameId: String, winnerId: String?): Result<Unit>
    suspend fun addMonsterToCatalog(serverUrl: String, monster: CatalogMonster, authToken: String): Result<CatalogMonster>
    suspend fun disconnect()
    fun close()
}

internal class GameClientSessionConnection(
    private val client: GameClient
) : SessionConnection {
    override val rawClient: GameClient = client
    override val connectionState: StateFlow<ConnectionState> = client.connectionState
    override val gameState: StateFlow<GameState?> = client.gameState
    override val myPlayerId: StateFlow<PlayerId?> = client.myPlayerId
    override val errors: SharedFlow<String> = client.errors
    override val reconnectAttempt: StateFlow<Int> = client.reconnectAttempt
    override val reconnectToken: StateFlow<String?> = client.reconnectToken
    override val gameDeleted: SharedFlow<String> = client.gameDeleted
    override val serverTimeOffsetMs: StateFlow<Long> = client.serverTimeOffsetMs

    override fun nudge() = client.nudge()
    override suspend fun sendEvent(event: GameEvent): Result<Unit> = client.sendEvent(event)
    override suspend fun kickPlayer(targetPlayerId: PlayerId): Result<Unit> = client.kickPlayer(targetPlayerId)
    override suspend fun sendTransferHost(targetPlayerId: PlayerId): Result<Unit> =
        client.sendTransferHost(targetPlayerId)
    override suspend fun sendDeleteGame(): Result<Unit> = client.sendDeleteGame()
    override suspend fun sendSwapPlayers(player1: PlayerId, player2: PlayerId): Result<Unit> =
        client.sendSwapPlayers(player1, player2)
    override suspend fun sendGameOver(gameId: String, winnerId: String?): Result<Unit> =
        client.sendGameOver(gameId, winnerId)
    override suspend fun addMonsterToCatalog(
        serverUrl: String,
        monster: CatalogMonster,
        authToken: String
    ): Result<CatalogMonster> = client.addMonsterToCatalog(serverUrl, monster, authToken)
    override suspend fun disconnect() = client.disconnect()
    override fun close() = client.close()
}

/**
 * Network gateway encapsulating one-off `.use {}` calls and long-lived game socket creation.
 */
internal interface RemoteGateway {
    suspend fun createSessionConnection(
        serverUrl: String,
        playerMeta: PlayerMeta,
        superMunchkin: Boolean,
        turnTimerSeconds: Int,
        authToken: String?
    ): Result<Pair<SessionConnection, GameState>>

    suspend fun connectSessionConnection(
        wsUrl: String,
        joinCode: String,
        playerMeta: PlayerMeta,
        reconnectToken: String?,
        authToken: String?
    ): Result<Pair<SessionConnection, GameState>>

    suspend fun login(serverUrl: String, email: String, password: String): Result<AuthSuccessMessage>
    suspend fun loginWithToken(serverUrl: String, token: String): Result<AuthSuccessMessage>
    suspend fun register(
        serverUrl: String,
        username: String,
        email: String,
        password: String,
        avatarId: Int,
        gender: Gender
    ): Result<AuthSuccessMessage>

    suspend fun updateProfile(
        serverUrl: String,
        userId: String,
        username: String?,
        password: String?,
        token: String,
        avatarId: Int?,
        gender: Gender?,
        currentPassword: String?
    ): Result<ProfileUpdatedMessage>

    suspend fun deleteAccount(serverUrl: String, password: String, token: String): Result<Unit>
    suspend fun getHistory(serverUrl: String, userId: String, authToken: String): Result<HistoryResult>
    suspend fun getLeaderboard(serverUrl: String, authToken: String?): Result<LeaderboardResult>
    suspend fun getHostedGames(serverUrl: String, token: String): Result<List<HostedGame>>
    suspend fun deleteHostedGame(serverUrl: String, token: String, gameId: String): Result<Unit>
    suspend fun listGames(serverUrl: String): Result<List<DiscoveredGame>>
    suspend fun searchMonsters(
        serverUrl: String,
        query: String,
        expansion: String?,
        limit: Int,
        offset: Int
    ): Result<List<CatalogMonster>>

    suspend fun addMonsterToCatalog(
        serverUrl: String,
        monster: CatalogMonster,
        authToken: String
    ): Result<CatalogMonster>
}

internal class GameClientRemoteGateway(
    private val clientFactory: () -> GameClient
) : RemoteGateway {

    override suspend fun createSessionConnection(
        serverUrl: String,
        playerMeta: PlayerMeta,
        superMunchkin: Boolean,
        turnTimerSeconds: Int,
        authToken: String?
    ): Result<Pair<SessionConnection, GameState>> {
        val client = clientFactory()
        return try {
            val result = client.createGame(
                serverUrl = serverUrl,
                playerMeta = playerMeta,
                superMunchkin = superMunchkin,
                turnTimerSeconds = turnTimerSeconds,
                authToken = authToken
            )
            result.fold(
                onSuccess = { state -> Result.success(GameClientSessionConnection(client) to state) },
                onFailure = { error ->
                    runCatching { client.close() }
                    Result.failure(error)
                }
            )
        } catch (e: CancellationException) {
            runCatching { client.close() }
            throw e
        } catch (e: Exception) {
            runCatching { client.close() }
            Result.failure(e)
        }
    }

    override suspend fun connectSessionConnection(
        wsUrl: String,
        joinCode: String,
        playerMeta: PlayerMeta,
        reconnectToken: String?,
        authToken: String?
    ): Result<Pair<SessionConnection, GameState>> {
        val client = clientFactory()
        return try {
            val result = client.connect(
                wsUrl = wsUrl,
                joinCode = joinCode,
                playerMeta = playerMeta,
                reconnectToken = reconnectToken,
                authToken = authToken
            )
            result.fold(
                onSuccess = { state -> Result.success(GameClientSessionConnection(client) to state) },
                onFailure = { error ->
                    runCatching { client.close() }
                    Result.failure(error)
                }
            )
        } catch (e: CancellationException) {
            runCatching { client.close() }
            throw e
        } catch (e: Exception) {
            runCatching { client.close() }
            Result.failure(e)
        }
    }

    override suspend fun login(serverUrl: String, email: String, password: String): Result<AuthSuccessMessage> =
        withOneOffClient { it.login(serverUrl, email, password) }

    override suspend fun loginWithToken(serverUrl: String, token: String): Result<AuthSuccessMessage> =
        withOneOffClient { it.loginWithToken(serverUrl, token) }

    override suspend fun register(
        serverUrl: String,
        username: String,
        email: String,
        password: String,
        avatarId: Int,
        gender: Gender
    ): Result<AuthSuccessMessage> = withOneOffClient {
        it.register(serverUrl, username, email, password, avatarId, gender)
    }

    override suspend fun updateProfile(
        serverUrl: String,
        userId: String,
        username: String?,
        password: String?,
        token: String,
        avatarId: Int?,
        gender: Gender?,
        currentPassword: String?
    ): Result<ProfileUpdatedMessage> = withOneOffClient {
        it.updateProfile(serverUrl, userId, username, password, token, avatarId, gender, currentPassword)
    }

    override suspend fun deleteAccount(serverUrl: String, password: String, token: String): Result<Unit> =
        withOneOffClient { it.deleteAccount(serverUrl, password, token) }

    override suspend fun getHistory(serverUrl: String, userId: String, authToken: String): Result<HistoryResult> =
        withOneOffClient { it.getHistory(serverUrl, userId, authToken) }

    override suspend fun getLeaderboard(serverUrl: String, authToken: String?): Result<LeaderboardResult> =
        withOneOffClient { it.getLeaderboard(serverUrl, authToken) }

    override suspend fun getHostedGames(serverUrl: String, token: String): Result<List<HostedGame>> =
        withOneOffClient { it.getHostedGames(serverUrl, token) }

    override suspend fun deleteHostedGame(serverUrl: String, token: String, gameId: String): Result<Unit> =
        withOneOffClient { it.deleteHostedGame(serverUrl, token, gameId) }

    override suspend fun listGames(serverUrl: String): Result<List<DiscoveredGame>> =
        withOneOffClient { it.listGames(serverUrl) }

    override suspend fun searchMonsters(
        serverUrl: String,
        query: String,
        expansion: String?,
        limit: Int,
        offset: Int
    ): Result<List<CatalogMonster>> = withOneOffClient {
        it.searchMonsters(serverUrl, query, expansion, limit, offset)
    }

    override suspend fun addMonsterToCatalog(
        serverUrl: String,
        monster: CatalogMonster,
        authToken: String
    ): Result<CatalogMonster> = withOneOffClient {
        it.addMonsterToCatalog(serverUrl, monster, authToken)
    }

    private suspend inline fun <T> withOneOffClient(
        crossinline block: suspend (GameClient) -> Result<T>
    ): Result<T> {
        return try {
            clientFactory().use { client ->
                block(client)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
