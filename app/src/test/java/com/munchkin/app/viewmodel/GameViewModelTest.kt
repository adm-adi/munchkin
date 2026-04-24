package com.munchkin.app.viewmodel

import com.munchkin.app.R
import com.munchkin.app.data.PlayerIdentityStore
import com.munchkin.app.data.SavedGame
import com.munchkin.app.data.SavedGameStore
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.network.GAME_DELETED_BY_HOST_REASON
import com.munchkin.app.network.RealtimeSession
import com.munchkin.app.network.RealtimeSessionFactory
import com.munchkin.app.core.GameEvent
import com.munchkin.app.core.GameId
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameSettings
import com.munchkin.app.core.GameState
import com.munchkin.app.core.GameStart
import com.munchkin.app.core.Gender
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerMeta
import com.munchkin.app.core.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun joinGameReusesSavedPlayerIdAndSavesGame() = runTest(mainDispatcherRule.testDispatcher) {
        val playerId = PlayerId("saved-player")
        val gameState = gameState(playerId = playerId, phase = GamePhase.IN_GAME)
        val savedGameStore = FakeSavedGameStore()
        val identityStore = FakePlayerIdentityStore().apply {
            savePlayerId("ABCD12", playerId.value)
        }
        val session = FakeRealtimeSession(connectResult = Result.success(gameState))
        val viewModel = viewModel(
            savedGameStore = savedGameStore,
            playerIdentityStore = identityStore,
            realtimeSessionFactory = FakeRealtimeSessionFactory(session),
            eventFactory = GameEventFactory(newId = { "event-id" }, now = { 123L }),
            playerIdFactory = FixedPlayerIdFactory(PlayerId("unused-generated-player"))
        )
        val events = mutableListOf<GameUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }

        viewModel.joinGame(
            wsUrl = "wss://example.test:8765",
            joinCode = "ABCD12",
            name = "Ana",
            avatarId = 2,
            gender = Gender.F,
            userProfile = null
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(gameState, viewModel.uiState.value.gameState)
        assertEquals(playerId, viewModel.uiState.value.myPlayerId)
        assertEquals(false, viewModel.uiState.value.isHost)
        assertEquals(listOf(SavedCall(gameState, playerId, false)), savedGameStore.savedCalls)
        assertEquals(playerId, session.lastConnectMeta?.playerId)
        assertTrue(events.contains(GameUiEvent.Navigate(GameDestination.BOARD)))
    }

    @Test
    fun createGameSavesHostStateAndNavigatesToLobby() = runTest(mainDispatcherRule.testDispatcher) {
        val hostId = PlayerId("host-player")
        val gameState = gameState(playerId = hostId, phase = GamePhase.LOBBY)
        val savedGameStore = FakeSavedGameStore()
        val session = FakeRealtimeSession(createResult = Result.success(gameState))
        val viewModel = viewModel(
            savedGameStore = savedGameStore,
            playerIdentityStore = FakePlayerIdentityStore(),
            realtimeSessionFactory = FakeRealtimeSessionFactory(session),
            eventFactory = GameEventFactory(newId = { "event-id" }, now = { 123L }),
            playerIdFactory = FixedPlayerIdFactory(hostId)
        )
        val events = mutableListOf<GameUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }

        viewModel.createGame(
            name = "Host",
            avatarId = 1,
            gender = Gender.M,
            timerSeconds = 45,
            superMunchkin = true,
            userProfile = null
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(gameState, viewModel.uiState.value.gameState)
        assertEquals(true, viewModel.uiState.value.isHost)
        assertEquals(true, session.lastCreateSuperMunchkin)
        assertEquals(45, session.lastCreateTurnTimerSeconds)
        assertEquals(listOf(SavedCall(gameState, hostId, true)), savedGameStore.savedCalls)
        assertEquals(hostId, viewModel.uiState.value.myPlayerId)
        assertTrue(events.contains(GameUiEvent.Navigate(GameDestination.LOBBY)))
    }

    @Test
    fun joinGameCreatesAndPersistsPlayerIdWhenNoSavedIdentityExists() = runTest(mainDispatcherRule.testDispatcher) {
        val generatedPlayerId = PlayerId("generated-player")
        val gameState = gameState(playerId = generatedPlayerId, phase = GamePhase.LOBBY)
        val identityStore = FakePlayerIdentityStore()
        val session = FakeRealtimeSession(connectResult = Result.success(gameState))
        val viewModel = viewModel(
            savedGameStore = FakeSavedGameStore(),
            playerIdentityStore = identityStore,
            realtimeSessionFactory = FakeRealtimeSessionFactory(session),
            eventFactory = GameEventFactory(newId = { "event-id" }, now = { 123L }),
            playerIdFactory = FixedPlayerIdFactory(generatedPlayerId)
        )

        viewModel.joinGame(
            wsUrl = "wss://example.test:8765",
            joinCode = "ABCD12",
            name = "Ana",
            avatarId = 2,
            gender = Gender.F,
            userProfile = null
        )
        advanceUntilIdle()

        assertEquals(generatedPlayerId.value, identityStore.getPlayerId("ABCD12"))
        assertEquals(generatedPlayerId, session.lastConnectMeta?.playerId)
        assertEquals(generatedPlayerId, viewModel.uiState.value.myPlayerId)
    }

    @Test
    fun hostActionSendsSharedGameplayEventThroughRealtimeSession() = runTest(mainDispatcherRule.testDispatcher) {
        val hostId = PlayerId("host-player")
        val gameState = gameState(playerId = hostId, phase = GamePhase.LOBBY)
        val session = FakeRealtimeSession(createResult = Result.success(gameState))
        val viewModel = viewModel(
            savedGameStore = FakeSavedGameStore(),
            playerIdentityStore = FakePlayerIdentityStore(),
            realtimeSessionFactory = FakeRealtimeSessionFactory(session),
            eventFactory = GameEventFactory(newId = { "start-event" }, now = { 456L }),
            playerIdFactory = FixedPlayerIdFactory(hostId)
        )

        viewModel.createGame(
            name = "Host",
            avatarId = 1,
            gender = Gender.M,
            userProfile = null
        )
        advanceUntilIdle()
        viewModel.startGame()
        advanceUntilIdle()

        val event = session.sentEvents.single() as GameStart
        assertEquals("start-event", event.eventId)
        assertEquals(hostId, event.actorId)
        assertEquals(456L, event.timestamp)
    }

    @Test
    fun joinGameUsesFriendlyConnectionErrorWhenConnectFails() = runTest(mainDispatcherRule.testDispatcher) {
        val session = FakeRealtimeSession(
            connectResult = Result.failure(IllegalStateException("timeout while connecting"))
        )
        val viewModel = viewModel(
            savedGameStore = FakeSavedGameStore(),
            playerIdentityStore = FakePlayerIdentityStore(),
            realtimeSessionFactory = FakeRealtimeSessionFactory(session),
            playerIdFactory = FixedPlayerIdFactory(PlayerId("generated-player"))
        )

        viewModel.joinGame(
            wsUrl = "wss://example.test:8765",
            joinCode = "ABCD12",
            name = "Ana",
            avatarId = 2,
            gender = Gender.F,
            userProfile = null
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("connection-timeout", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.gameState)
    }

    @Test
    fun resumeSavedGameUsesFriendlyConnectionErrorWhenReconnectFails() = runTest(mainDispatcherRule.testDispatcher) {
        val playerId = PlayerId("saved-player")
        val savedState = gameState(playerId = playerId, phase = GamePhase.IN_GAME)
        val savedGameStore = FakeSavedGameStore(
            initialSavedGame = SavedGame(
                gameState = savedState,
                myPlayerId = playerId,
                isHost = false,
                savedAt = 123L
            )
        )
        val session = FakeRealtimeSession(
            connectResult = Result.failure(IllegalStateException("server refused connection"))
        )
        val viewModel = viewModel(
            savedGameStore = savedGameStore,
            playerIdentityStore = FakePlayerIdentityStore(),
            realtimeSessionFactory = FakeRealtimeSessionFactory(session)
        )
        advanceUntilIdle()

        viewModel.resumeSavedGame(userProfile = null)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("server-unavailable", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.gameState)
    }

    @Test
    fun failedGameplaySendEmitsLocalizedConnectionError() = runTest(mainDispatcherRule.testDispatcher) {
        val hostId = PlayerId("host-player")
        val gameState = gameState(playerId = hostId, phase = GamePhase.LOBBY)
        val session = FakeRealtimeSession(
            createResult = Result.success(gameState),
            sendEventResult = Result.failure(IllegalStateException("socket closed"))
        )
        val viewModel = viewModel(
            savedGameStore = FakeSavedGameStore(),
            playerIdentityStore = FakePlayerIdentityStore(),
            realtimeSessionFactory = FakeRealtimeSessionFactory(session),
            eventFactory = GameEventFactory(newId = { "start-event" }, now = { 456L }),
            playerIdFactory = FixedPlayerIdFactory(hostId)
        )
        val events = mutableListOf<GameUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }

        viewModel.createGame(
            name = "Host",
            avatarId = 1,
            gender = Gender.M,
            userProfile = null
        )
        advanceUntilIdle()
        viewModel.startGame()
        advanceUntilIdle()

        assertTrue(events.contains(GameUiEvent.ShowError("connection-failed")))
    }

    @Test
    fun swapPlayersFailureSetsReorderError() = runTest(mainDispatcherRule.testDispatcher) {
        val hostId = PlayerId("host-player")
        val guestId = PlayerId("guest-player")
        val gameState = gameState(playerId = hostId, phase = GamePhase.LOBBY).copy(
            players = mapOf(
                hostId to PlayerState(playerId = hostId, name = "Host"),
                guestId to PlayerState(playerId = guestId, name = "Guest")
            ),
            playerOrder = listOf(hostId, guestId)
        )
        val session = FakeRealtimeSession(
            createResult = Result.success(gameState),
            sendSwapPlayersResult = Result.failure(IllegalStateException("not allowed"))
        )
        val viewModel = viewModel(
            savedGameStore = FakeSavedGameStore(),
            playerIdentityStore = FakePlayerIdentityStore(),
            realtimeSessionFactory = FakeRealtimeSessionFactory(session),
            playerIdFactory = FixedPlayerIdFactory(hostId)
        )

        viewModel.createGame(
            name = "Host",
            avatarId = 1,
            gender = Gender.M,
            userProfile = null
        )
        advanceUntilIdle()
        viewModel.swapPlayers(hostId, guestId)
        advanceUntilIdle()

        assertEquals(hostId to guestId, session.lastSwapPlayers)
        assertEquals("reorder: not allowed", viewModel.uiState.value.error)
    }

    @Test
    fun gameDeletedSignalClearsSavedStateAndNavigatesHome() = runTest(mainDispatcherRule.testDispatcher) {
        val hostId = PlayerId("host-player")
        val gameState = gameState(playerId = hostId, phase = GamePhase.LOBBY)
        val savedGameStore = FakeSavedGameStore()
        val session = FakeRealtimeSession(createResult = Result.success(gameState))
        val viewModel = viewModel(
            savedGameStore = savedGameStore,
            playerIdentityStore = FakePlayerIdentityStore(),
            realtimeSessionFactory = FakeRealtimeSessionFactory(session),
            playerIdFactory = FixedPlayerIdFactory(hostId)
        )
        val events = mutableListOf<GameUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }

        viewModel.createGame(
            name = "Host",
            avatarId = 1,
            gender = Gender.M,
            userProfile = null
        )
        advanceUntilIdle()
        session.gameDeleted.emit(GAME_DELETED_BY_HOST_REASON)
        advanceUntilIdle()

        assertEquals(1, savedGameStore.deleteAllCalls)
        assertNull(viewModel.uiState.value.gameState)
        assertTrue(events.contains(GameUiEvent.ShowMessage("game-deleted-by-host")))
        assertTrue(events.contains(GameUiEvent.Navigate(GameDestination.HOME)))
    }

    @Test
    fun confirmWinSendsGameOverThroughRealtimeSession() = runTest(mainDispatcherRule.testDispatcher) {
        val hostId = PlayerId("host-player")
        val gameState = gameState(playerId = hostId, phase = GamePhase.IN_GAME)
        val session = FakeRealtimeSession(createResult = Result.success(gameState))
        val viewModel = viewModel(
            savedGameStore = FakeSavedGameStore(),
            playerIdentityStore = FakePlayerIdentityStore(),
            realtimeSessionFactory = FakeRealtimeSessionFactory(session),
            playerIdFactory = FixedPlayerIdFactory(hostId)
        )

        viewModel.createGame(
            name = "Host",
            avatarId = 1,
            gender = Gender.M,
            userProfile = null
        )
        advanceUntilIdle()
        viewModel.confirmWin(hostId)
        advanceUntilIdle()

        assertEquals("game-1" to hostId.value, session.lastGameOver)
        assertNull(viewModel.uiState.value.pendingWinnerId)
    }

    @Test
    fun confirmWinFailureRestoresPendingWinnerAndEmitsError() = runTest(mainDispatcherRule.testDispatcher) {
        val hostId = PlayerId("host-player")
        val gameState = gameState(playerId = hostId, phase = GamePhase.IN_GAME)
        val session = FakeRealtimeSession(
            createResult = Result.success(gameState),
            sendGameOverResult = Result.failure(IllegalStateException("socket closed"))
        )
        val viewModel = viewModel(
            savedGameStore = FakeSavedGameStore(),
            playerIdentityStore = FakePlayerIdentityStore(),
            realtimeSessionFactory = FakeRealtimeSessionFactory(session),
            playerIdFactory = FixedPlayerIdFactory(hostId)
        )
        val events = mutableListOf<GameUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }

        viewModel.createGame(
            name = "Host",
            avatarId = 1,
            gender = Gender.M,
            userProfile = null
        )
        advanceUntilIdle()
        viewModel.confirmWin(hostId)
        advanceUntilIdle()

        assertEquals(hostId, viewModel.uiState.value.pendingWinnerId)
        assertTrue(events.contains(GameUiEvent.ShowError("connection-lost")))
    }

    private fun viewModel(
        savedGameStore: SavedGameStore = FakeSavedGameStore(),
        playerIdentityStore: PlayerIdentityStore = FakePlayerIdentityStore(),
        realtimeSessionFactory: RealtimeSessionFactory = FakeRealtimeSessionFactory(FakeRealtimeSession()),
        stateTransitionAnalyzer: GameStateTransitionAnalyzer = GameStateTransitionAnalyzer(),
        eventFactory: GameEventFactory = GameEventFactory(newId = { "event-id" }, now = { 123L }),
        playerIdFactory: PlayerIdFactory = FixedPlayerIdFactory(PlayerId("generated-player")),
        textProvider: GameTextProvider = FakeGameTextProvider()
    ): GameViewModel {
        return GameViewModel(
            savedGameStore = savedGameStore,
            playerIdentityStore = playerIdentityStore,
            realtimeSessionFactory = realtimeSessionFactory,
            stateTransitionAnalyzer = stateTransitionAnalyzer,
            eventFactory = eventFactory,
            playerIdFactory = playerIdFactory,
            logger = NoOpGameLogger,
            textProvider = textProvider,
            friendlyErrorMapper = FriendlyErrorMapper(textProvider)
        )
    }

    private class FakeSavedGameStore(
        initialSavedGame: SavedGame? = null
    ) : SavedGameStore {
        private val savedGame = MutableStateFlow(initialSavedGame)
        val savedCalls = mutableListOf<SavedCall>()
        var deleteAllCalls = 0
            private set

        override fun getLatestSavedGame() = savedGame

        override suspend fun saveGame(gameState: GameState, myPlayerId: PlayerId, isHost: Boolean) {
            savedCalls += SavedCall(gameState, myPlayerId, isHost)
        }

        override suspend fun deleteSavedGame(gameId: String) = Unit

        override suspend fun deleteAllSavedGames() {
            deleteAllCalls += 1
            savedGame.value = null
        }
    }

    private class FakePlayerIdentityStore : PlayerIdentityStore {
        private val playerIdsByCode = mutableMapOf<String, String>()

        override fun savePlayerId(joinCode: String, playerId: String) {
            playerIdsByCode[joinCode.uppercase()] = playerId
        }

        override fun getPlayerId(joinCode: String): String? = playerIdsByCode[joinCode.uppercase()]

        override fun clearPlayerId(joinCode: String) {
            playerIdsByCode.remove(joinCode.uppercase())
        }
    }

    private class FixedPlayerIdFactory(
        private val playerId: PlayerId
    ) : PlayerIdFactory {
        override fun create(): PlayerId = playerId
    }

    private class FakeRealtimeSessionFactory(
        private val session: FakeRealtimeSession
    ) : RealtimeSessionFactory {
        override fun create(): RealtimeSession = session
    }

    private class FakeRealtimeSession(
        private val createResult: Result<GameState> = Result.failure(IllegalStateException("create not configured")),
        private val connectResult: Result<GameState> = Result.failure(IllegalStateException("connect not configured")),
        private val sendEventResult: Result<Unit> = Result.success(Unit),
        private val sendGameOverResult: Result<Unit> = Result.success(Unit),
        private val sendSwapPlayersResult: Result<Unit> = Result.success(Unit)
    ) : RealtimeSession {
        override val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        override val gameState = MutableStateFlow<GameState?>(null)
        override val myPlayerId = MutableStateFlow<PlayerId?>(null)
        override val errors = MutableSharedFlow<String>()
        override val gameDeleted = MutableSharedFlow<String>()
        override val reconnectAttempt = MutableStateFlow(0)
        var lastConnectMeta: PlayerMeta? = null
            private set
        var lastCreateSuperMunchkin: Boolean? = null
            private set
        var lastCreateTurnTimerSeconds: Int? = null
            private set
        var lastGameOver: Pair<String, String>? = null
            private set
        var lastSwapPlayers: Pair<PlayerId, PlayerId>? = null
            private set
        val sentEvents = mutableListOf<GameEvent>()

        override fun isConnected(): Boolean = true

        override suspend fun createGame(
            serverUrl: String,
            playerMeta: PlayerMeta,
            superMunchkin: Boolean,
            turnTimerSeconds: Int
        ): Result<GameState> {
            lastCreateSuperMunchkin = superMunchkin
            lastCreateTurnTimerSeconds = turnTimerSeconds
            myPlayerId.value = playerMeta.playerId
            gameState.value = createResult.getOrNull()
            return createResult
        }

        override suspend fun connect(
            wsUrl: String,
            joinCode: String,
            playerMeta: PlayerMeta
        ): Result<GameState> {
            lastConnectMeta = playerMeta
            myPlayerId.value = playerMeta.playerId
            gameState.value = connectResult.getOrNull()
            return connectResult
        }

        override suspend fun sendEvent(event: GameEvent): Result<Unit> {
            sentEvents += event
            return sendEventResult
        }

        override suspend fun kickPlayer(targetPlayerId: PlayerId): Result<Unit> = Result.success(Unit)

        override suspend fun sendDeleteGame(): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect() = Unit

        override suspend fun sendGameOver(gameId: String, winnerId: String): Result<Unit> {
            lastGameOver = gameId to winnerId
            return sendGameOverResult
        }

        override suspend fun sendSwapPlayers(player1: PlayerId, player2: PlayerId): Result<Unit> {
            lastSwapPlayers = player1 to player2
            return sendSwapPlayersResult
        }
    }

    private data class SavedCall(
        val gameState: GameState,
        val myPlayerId: PlayerId,
        val isHost: Boolean
    )

    private class FakeGameTextProvider : GameTextProvider {
        override fun get(resId: Int, vararg args: Any): String {
            val template = when (resId) {
                R.string.connection_failed -> "connection-failed"
                R.string.error_connection_timeout -> "connection-timeout"
                R.string.error_server_unavailable -> "server-unavailable"
                R.string.error_server_not_found -> "server-not-found"
                R.string.error_connection_lost_retry -> "connection-lost"
                R.string.error_unauthorized_game -> "unauthorized"
                R.string.error_game_not_found -> "game-not-found"
                R.string.error_game_full_short -> "game-full"
                R.string.error_connection_retry -> "connection-retry"
                R.string.game_deleted_by_host -> "game-deleted-by-host"
                R.string.error_unknown -> "unknown"
                R.string.error_connect_format -> "connect: %1\$s"
                R.string.error_prefix_format -> "error: %1\$s"
                R.string.error_reorder_format -> "reorder: %1\$s"
                R.string.player_fallback -> "player"
                R.string.host -> "host"
                else -> "string-$resId"
            }
            return template.format(*args)
        }
    }

    private fun gameState(playerId: PlayerId, phase: GamePhase): GameState {
        return GameState(
            gameId = GameId("game-1"),
            joinCode = "ABCD12",
            hostId = playerId,
            players = mapOf(playerId to PlayerState(playerId = playerId, name = "Ana")),
            phase = phase,
            settings = GameSettings()
        )
    }
}
