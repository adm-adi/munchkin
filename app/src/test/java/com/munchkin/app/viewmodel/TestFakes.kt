package com.munchkin.app.viewmodel

import com.munchkin.app.core.GameEvent
import com.munchkin.app.core.GameState
import com.munchkin.app.core.Gender
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerMeta
import com.munchkin.app.data.SavedGame
import com.munchkin.app.di.StringResolver
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

internal class FakeStringResolver : StringResolver {
    override fun get(resId: Int, vararg args: Any): String {
        return if (args.isEmpty()) {
            "res:$resId"
        } else {
            "res:$resId(${args.joinToString(",")})"
        }
    }
}

internal class FakeSessionStore : SessionStore {
    var user: UserProfile? = null
    var tokenValue: String? = null
    val playerIds = mutableMapOf<String, String>()
    val reconnectTokens = mutableMapOf<String, String>()
    var clearSessionCalls = 0

    override fun getSession(): UserProfile? = user
    override fun saveSession(user: UserProfile) {
        this.user = user
    }

    override fun getAuthToken(): String? = tokenValue
    override fun saveAuthToken(token: String) {
        tokenValue = token
    }

    override fun savePlayerId(joinCode: String, playerId: String) {
        playerIds[joinCode.uppercase()] = playerId
    }

    override fun getPlayerId(joinCode: String): String? = playerIds[joinCode.uppercase()]

    override fun saveReconnectToken(joinCode: String, token: String) {
        reconnectTokens[joinCode.uppercase()] = token
    }

    override fun getReconnectToken(joinCode: String): String? = reconnectTokens[joinCode.uppercase()]

    override fun clearSession() {
        clearSessionCalls++
        user = null
        tokenValue = null
        playerIds.clear()
        reconnectTokens.clear()
    }
}

internal class FakeSavedGameStore : SavedGameStore {
    private val flow = MutableStateFlow<SavedGame?>(null)
    var deleteAllCalls = 0

    override fun getLatestSavedGame(): Flow<SavedGame?> = flow

    override suspend fun saveGame(gameState: GameState, myPlayerId: PlayerId, isHost: Boolean) {
        flow.value = SavedGame(
            gameState = gameState,
            myPlayerId = myPlayerId,
            isHost = isHost,
            savedAt = 1234L
        )
    }

    override suspend fun deleteAllSavedGames() {
        deleteAllCalls++
        flow.value = null
    }
}

internal class FakeUpdateGateway : UpdateGateway {
    var nextCheckResult: UpdateResult = UpdateResult.NoUpdate
    var nextDownloadResult: Result<Unit> = Result.success(Unit)

    override suspend fun checkForUpdate(): UpdateResult = nextCheckResult
    override suspend fun downloadAndInstall(updateInfo: UpdateInfo): Result<Unit> = nextDownloadResult
}

internal class FakeLargeNumbersPrefs(initial: Boolean = false) : LargeNumbersPrefs {
    private val _largeNumbersMode = MutableStateFlow(initial)
    override val largeNumbersMode: StateFlow<Boolean> = _largeNumbersMode.asStateFlow()

    override fun setLargeNumbersMode(enabled: Boolean) {
        _largeNumbersMode.value = enabled
    }
}

internal class FakeSessionConnection(
    initialState: GameState? = null,
    initialPlayerId: PlayerId? = null,
    initialConnectionState: ConnectionState = ConnectionState.CONNECTED
) : SessionConnection {
    override val rawClient: GameClient? = null

    val mutableConnectionState = MutableStateFlow(initialConnectionState)
    override val connectionState: StateFlow<ConnectionState> = mutableConnectionState.asStateFlow()

    val mutableGameState = MutableStateFlow(initialState)
    override val gameState: StateFlow<GameState?> = mutableGameState.asStateFlow()

    val mutableMyPlayerId = MutableStateFlow(initialPlayerId)
    override val myPlayerId: StateFlow<PlayerId?> = mutableMyPlayerId.asStateFlow()

    val mutableErrors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val errors: SharedFlow<String> = mutableErrors.asSharedFlow()

    val mutableReconnectAttempt = MutableStateFlow(0)
    override val reconnectAttempt: StateFlow<Int> = mutableReconnectAttempt.asStateFlow()

    val mutableReconnectToken = MutableStateFlow<String?>("rc-token")
    override val reconnectToken: StateFlow<String?> = mutableReconnectToken.asStateFlow()

    val mutableGameDeleted = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val gameDeleted: SharedFlow<String> = mutableGameDeleted.asSharedFlow()

    val mutableServerTimeOffsetMs = MutableStateFlow(0L)
    override val serverTimeOffsetMs: StateFlow<Long> = mutableServerTimeOffsetMs.asStateFlow()

    val sentEvents = mutableListOf<GameEvent>()
    var sendEventResult: Result<Unit> = Result.success(Unit)
    var sendGameOverResult: Result<Unit> = Result.success(Unit)
    var sendDeleteGameResult: Result<Unit> = Result.success(Unit)
    val transferHostTargets = mutableListOf<PlayerId>()
    val kickedTargets = mutableListOf<PlayerId>()
    val recordedWinners = mutableListOf<Pair<String, String?>>()
    var nudgeCount = 0
    var disconnectCount = 0
    var closeCount = 0

    override fun nudge() {
        nudgeCount++
    }

    override suspend fun sendEvent(event: GameEvent): Result<Unit> {
        sentEvents.add(event)
        return sendEventResult
    }

    override suspend fun kickPlayer(targetPlayerId: PlayerId): Result<Unit> {
        kickedTargets.add(targetPlayerId)
        return Result.success(Unit)
    }

    override suspend fun sendTransferHost(targetPlayerId: PlayerId): Result<Unit> {
        transferHostTargets.add(targetPlayerId)
        return Result.success(Unit)
    }

    override suspend fun sendDeleteGame(): Result<Unit> = sendDeleteGameResult

    override suspend fun sendSwapPlayers(player1: PlayerId, player2: PlayerId): Result<Unit> =
        Result.success(Unit)

    override suspend fun sendGameOver(gameId: String, winnerId: String?): Result<Unit> {
        recordedWinners.add(gameId to winnerId)
        return sendGameOverResult
    }

    override suspend fun addMonsterToCatalog(
        serverUrl: String,
        monster: CatalogMonster,
        authToken: String
    ): Result<CatalogMonster> = Result.success(monster.copy(id = "created-id"))

    override suspend fun disconnect() {
        disconnectCount++
    }

    override fun close() {
        closeCount++
    }
}

internal class FakeRemoteGateway : RemoteGateway {
    var lastConnectUrl: String? = null
    var lastConnectJoinCode: String? = null
    var lastConnectMeta: PlayerMeta? = null
    var nextCreateConnection: suspend () -> Result<Pair<SessionConnection, GameState>> = {
        Result.failure(UnsupportedOperationException("Not stubbed"))
    }
    var nextConnectConnection: suspend () -> Result<Pair<SessionConnection, GameState>> = {
        Result.failure(UnsupportedOperationException("Not stubbed"))
    }
    var nextLogin: suspend () -> Result<AuthSuccessMessage> = {
        Result.failure(UnsupportedOperationException("Not stubbed"))
    }
    var nextLoginWithToken: suspend () -> Result<AuthSuccessMessage> = {
        Result.failure(UnsupportedOperationException("Not stubbed"))
    }
    var nextRegister: suspend () -> Result<AuthSuccessMessage> = {
        Result.failure(UnsupportedOperationException("Not stubbed"))
    }
    var nextUpdateProfile: suspend () -> Result<ProfileUpdatedMessage> = {
        Result.failure(UnsupportedOperationException("Not stubbed"))
    }
    var nextDeleteAccount: suspend () -> Result<Unit> = { Result.success(Unit) }
    var nextHistory: suspend () -> Result<HistoryResult> = {
        Result.success(HistoryResult(emptyList()))
    }
    var nextLeaderboard: suspend () -> Result<LeaderboardResult> = {
        Result.success(LeaderboardResult(emptyList()))
    }
    var nextHostedGames: suspend () -> Result<List<HostedGame>> = { Result.success(emptyList()) }
    var nextDeleteHostedGame: suspend () -> Result<Unit> = { Result.success(Unit) }
    var nextListGames: suspend () -> Result<List<DiscoveredGame>> = { Result.success(emptyList()) }
    var nextSearchMonsters: suspend (query: String, offset: Int) -> Result<List<CatalogMonster>> = { _, _ ->
        Result.success(emptyList())
    }
    var nextAddMonster: suspend (CatalogMonster) -> Result<CatalogMonster> = { monster ->
        Result.success(monster.copy(id = "catalog-1"))
    }

    override suspend fun createSessionConnection(
        serverUrl: String,
        playerMeta: PlayerMeta,
        superMunchkin: Boolean,
        turnTimerSeconds: Int,
        authToken: String?
    ): Result<Pair<SessionConnection, GameState>> = nextCreateConnection()

    override suspend fun connectSessionConnection(
        wsUrl: String,
        joinCode: String,
        playerMeta: PlayerMeta,
        reconnectToken: String?,
        authToken: String?
    ): Result<Pair<SessionConnection, GameState>> {
        lastConnectUrl = wsUrl
        lastConnectJoinCode = joinCode
        lastConnectMeta = playerMeta
        return nextConnectConnection()
    }

    override suspend fun login(serverUrl: String, email: String, password: String): Result<AuthSuccessMessage> =
        nextLogin()

    override suspend fun loginWithToken(serverUrl: String, token: String): Result<AuthSuccessMessage> =
        nextLoginWithToken()

    override suspend fun register(
        serverUrl: String,
        username: String,
        email: String,
        password: String,
        avatarId: Int,
        gender: Gender
    ): Result<AuthSuccessMessage> = nextRegister()

    override suspend fun updateProfile(
        serverUrl: String,
        userId: String,
        username: String?,
        password: String?,
        token: String,
        avatarId: Int?,
        gender: Gender?,
        currentPassword: String?
    ): Result<ProfileUpdatedMessage> = nextUpdateProfile()

    override suspend fun deleteAccount(serverUrl: String, password: String, token: String): Result<Unit> =
        nextDeleteAccount()

    override suspend fun getHistory(serverUrl: String, userId: String, authToken: String): Result<HistoryResult> =
        nextHistory()

    override suspend fun getLeaderboard(serverUrl: String, authToken: String?): Result<LeaderboardResult> =
        nextLeaderboard()

    override suspend fun getHostedGames(serverUrl: String, token: String): Result<List<HostedGame>> =
        nextHostedGames()

    override suspend fun deleteHostedGame(serverUrl: String, token: String, gameId: String): Result<Unit> =
        nextDeleteHostedGame()

    override suspend fun listGames(serverUrl: String): Result<List<DiscoveredGame>> =
        nextListGames()

    override suspend fun searchMonsters(
        serverUrl: String,
        query: String,
        expansion: String?,
        limit: Int,
        offset: Int
    ): Result<List<CatalogMonster>> = nextSearchMonsters(query, offset)

    override suspend fun addMonsterToCatalog(
        serverUrl: String,
        monster: CatalogMonster,
        authToken: String
    ): Result<CatalogMonster> = nextAddMonster(monster)
}
