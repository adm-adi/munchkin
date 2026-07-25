package com.munchkin.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.ui.components.DebugLogManager as DLog
import com.munchkin.app.MunchkinApp
import com.munchkin.app.core.*
import com.munchkin.app.data.GameRepository
import com.munchkin.app.data.SavedGame
import com.munchkin.app.data.SessionManager
import com.munchkin.app.network.*
import com.munchkin.app.update.UpdateChecker
import com.munchkin.app.update.UpdateInfo
import com.munchkin.app.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import java.util.UUID
import com.munchkin.app.network.DiscoveredGame
import com.munchkin.app.R

/**
 * Main ViewModel managing game state and network operations.
 * Handles both host and client roles.
 */
class GameViewModel : ViewModel() {

    companion object {
        // Not const: ServerConfig resolves the endpoint from BuildConfig at runtime
        // so dev/staging builds can override it.
        internal val SERVER_URL: String = ServerConfig.WS_URL
    }

    // ============== State ==============

    internal val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    internal val _events = MutableSharedFlow<GameUiEvent>()
    val events: SharedFlow<GameUiEvent> = _events.asSharedFlow()

    internal val _gameLog = MutableStateFlow<List<GameLogEntry>>(emptyList())
    val gameLog: StateFlow<List<GameLogEntry>> = _gameLog.asStateFlow()

    internal val _savedGame = MutableStateFlow<SavedGame?>(null)
    val savedGame: StateFlow<SavedGame?> = _savedGame.asStateFlow()

    internal val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    // ============== Update State ==============

    internal val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    internal val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    internal var updateChecker: UpdateChecker? = null

    // ============== Game Components ==============

    internal var gameClient: GameClient? = null
    internal var gameRepository: GameRepository? = null
    internal var sessionManager: SessionManager? = null

    internal var myPlayerId: PlayerId? = null
    internal var isHost: Boolean = false

    // ============== Initialization ==============

    init {
        initData()
        checkForUpdates()
    }

    private fun initData() {
        try {
            val context = MunchkinApp.context
            gameRepository = GameRepository(context)
            sessionManager = SessionManager(context)

            // Load Saved Game
            viewModelScope.launch {
                gameRepository?.getLatestSavedGame()?.collect { saved ->
                    _savedGame.value = saved
                }
            }

            // Restore Session & Auto-Login
            val savedProfile = sessionManager?.getSession()
            val savedToken = sessionManager?.getAuthToken()

            if (savedProfile != null) {
                _uiState.update { it.copy(userProfile = savedProfile) }

                // If we have a token, try to validate it / auto-login
                if (savedToken != null) {
                    performAutoLogin(savedToken)
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("GameViewModel", "Failed to init data", e)
        }
    }

    private fun performAutoLogin(token: String) {
        viewModelScope.launch {
            try {
                // We use a temp client to validate the token
                val client = GameClient()
                val result = client.loginWithToken(SERVER_URL, token)

                if (result.isSuccess) {
                    val authData = result.getOrNull()
                    if (authData != null) {
                        // Update profile with latest from server
                        _uiState.update { it.copy(userProfile = authData.user) }
                        sessionManager?.saveSession(authData.user)
                        // If server rotated token, save it (authData.token)
                        authData.token?.let { sessionManager?.saveAuthToken(it) }

                        android.util.Log.i("GameViewModel", "✅ Auto-login success for ${authData.user.username}")

                        // Fetch hosted games
                        fetchHostedGames()
                    }
                } else {
                    // Distinguish "the server rejected this token" from "we could not
                    // reach the server". Clearing the session on a transient network
                    // failure would log people out for no reason; keeping a rejected
                    // token leaves the user looking signed in while every
                    // authenticated request fails (which is what used to happen —
                    // the clearSession() call here was commented out).
                    val cause = result.exceptionOrNull()
                    val rejected = cause is ServerErrorException &&
                        cause.code == ErrorCode.AUTH_FAILED

                    if (rejected) {
                        android.util.Log.i("GameViewModel", "Session no longer valid — signing out")
                        sessionManager?.clearSession()
                        _uiState.update { it.copy(userProfile = null) }
                        _events.emit(GameUiEvent.ShowMessage("Tu sesión ha caducado. Vuelve a iniciar sesión."))
                    } else {
                        android.util.Log.w(
                            "GameViewModel",
                            "Could not validate session (offline?), keeping local profile: ${cause?.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GameViewModel", "Auto-login error", e)
            }
        }
        checkForUpdates()
    }

    /**
     * Clear error state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ============== Update Methods ==============

    // ============== System/Misc Actions ==============

    /**
     * Start the game (host only).
     */
    fun startGame() {
        if (!isHost) return

        sendPlayerEvent { playerId ->
            GameStart(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    /**
     * Swap two players' positions in the lobby (host only).
     */
    fun swapPlayers(player1: PlayerId, player2: PlayerId) {
        if (!isHost) return

        viewModelScope.launch {
            try {
                gameClient?.sendSwapPlayers(player1, player2)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Error al reordenar: ${e.message}") }
            }
        }
    }

    // ============== Client Actions ==============

    internal val _hostedGames = MutableStateFlow<List<HostedGame>>(emptyList())
    val hostedGames: StateFlow<List<HostedGame>> = _hostedGames.asStateFlow()

    // ============== System/Misc Actions ==============

    fun rollDiceForStart() {
        // Roll 1-6
        val result = (1..6).random()
        sendPlayerEvent { playerId ->
            PlayerRoll(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId, // Self
                result = result
            )
        }
    }

    fun rollForCombat(
        purpose: DiceRollPurpose = DiceRollPurpose.RUN_AWAY,
        manualResult: Int? = null,
        success: Boolean = false
    ) {
        // Roll 1-6 if manualResult is null (auto-roll)
        val result = manualResult ?: (1..6).random()

        sendPlayerEvent { playerId ->
            PlayerRoll(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId, // Self
                result = result,
                purpose = purpose,
                success = success
            )
        }
    }

    // ============== Navigation ==============

    fun navigateTo(screen: Screen) {
        _uiState.update { it.copy(screen = screen) }
    }

    fun goBack() {
        val currentScreen = _uiState.value.screen
        val newScreen = when (currentScreen) {
            Screen.PLAYER_DETAIL, Screen.COMBAT, Screen.CATALOG -> Screen.BOARD
            Screen.SETTINGS -> {
                if (_uiState.value.gameState != null) Screen.BOARD else Screen.HOME
            }
            else -> Screen.HOME
        }
        _uiState.update { it.copy(screen = newScreen) }
    }

    // ============== Cleanup ==============

    /**
     * Leave current game.
     */
    /**
     * Leave current game.
     */
    /**
     * Kick a disconnected player from the game (Host only).
     */
    fun kickPlayer(targetPlayerId: PlayerId) {
        if (!isHost) return
        viewModelScope.launch {
            gameClient?.kickPlayer(targetPlayerId)
        }
    }

    /**
     * Delete the game (Host only).
     */
    fun deleteGame() {
        if (!isHost) return

        viewModelScope.launch {
             try {
                 gameClient?.sendDeleteGame()
                 kotlinx.coroutines.delay(200)
             } catch (e: Exception) {
                 DLog.e("GameVM", "Failed to delete: ${e.message}")
             }
             leaveGame()
        }
    }

    /**
     * Leave current game.
     */
    fun leaveGame() {
        viewModelScope.launch {
            // ... (rest of leaveGame)
            if (isHost) {
                try {
                    val playerId = myPlayerId
                    if (playerId != null) {
                         gameClient?.sendEvent(
                             GameEnd(
                                 eventId = UUID.randomUUID().toString(),
                                 actorId = playerId,
                                 timestamp = System.currentTimeMillis(),
                                 winnerId = null // Aborted
                             )
                         )
                         // Give it a moment to send
                         kotlinx.coroutines.delay(500)
                    }
                } catch (e: Exception) {
                    // Ignore error on leave
                }
            }

            gameClient?.disconnect()
            gameClient = null

            _uiState.update {
                GameUiState(screen = Screen.HOME, userProfile = it.userProfile)
            }
        }
    }





    override fun onCleared() {
        super.onCleared()
        val client = gameClient
        if (client != null) {
            kotlinx.coroutines.runBlocking {
                client.disconnect()
            }
        }
    }

    // ============== Private Helpers ==============

    internal fun sendPlayerEvent(eventBuilder: (PlayerId) -> GameEvent) {
        viewModelScope.launch {
            val playerId = myPlayerId ?: return@launch
            val event = eventBuilder(playerId)

            val client = gameClient
            if (client != null && client.isConnected()) {
                // Network mode: send to server (the server will broadcast)
                val result = client.sendEvent(event)
                if (result.isFailure) {
                    _events.emit(GameUiEvent.ShowError("Error de conexión"))
                }
            }
        }
    }

    private var hasRecordedGame = false

    internal fun observeClientState() {
        var previousState: GameState? = null

        viewModelScope.launch {
            gameClient?.gameState?.collect { state ->
                state?.let { s ->
                    // Log Level Changes
                    previousState?.let { prev ->
                        s.players.forEach { (id, player) ->
                            val prevPlayer = prev.players[id]
                            if (prevPlayer != null && prevPlayer.level != player.level) {
                                val diff = player.level - prevPlayer.level
                                if (diff > 0) {
                                    addLogEntry("${player.name} subió a Nivel ${player.level}", LogType.LEVEL_UP)
                                } else {
                                    addLogEntry("${player.name} bajó a Nivel ${player.level}", LogType.INFO)
                                }
                            }
                        }

                        // Log Combat Result (Combat property cleared)
                        if (prev.combat != null && s.combat == null) {
                            addLogEntry("Combate finalizado", LogType.combat)
                        }
                    }
                    previousState = s

                    _uiState.update { it.copy(gameState = s) }

                    // HOST CHECK: Did someone reach max level?
                    if (isHost && s.phase == GamePhase.IN_GAME && !hasRecordedGame) {
                        val winner = s.players.values.find { it.level >= s.settings.maxLevel }
                        if (winner != null && _uiState.value.pendingWinnerId != winner.playerId) {
                            // Show confirmation dialog
                            _uiState.update { it.copy(pendingWinnerId = winner.playerId) }
                        } else if (winner == null && _uiState.value.pendingWinnerId != null) {
                            // Level went back down? Dismiss
                            _uiState.update { it.copy(pendingWinnerId = null) }
                        }
                    }

                    // Check for Game Over (Server confirmed)
                    if (s.phase == GamePhase.FINISHED && s.winnerId != null) {
                         // Ensure we don't record twice if server sent it back
                         hasRecordedGame = true
                    }

                    // Check for phase change
                    if (s.phase == GamePhase.IN_GAME && _uiState.value.screen == Screen.LOBBY) {
                        _uiState.update { it.copy(screen = Screen.BOARD) }
                    }
                }
            }
        }

        viewModelScope.launch {
            var prevConnState: ConnectionState? = null
            gameClient?.connectionState?.collect { connState ->
                // Emit success toast when we recover from a reconnect
                if (prevConnState == ConnectionState.RECONNECTING && connState == ConnectionState.CONNECTED) {
                    _events.emit(GameUiEvent.Reconnected)
                }
                prevConnState = connState
                _uiState.update {
                    it.copy(
                        connectionState = connState,
                        isReconnectFailed = connState == ConnectionState.FAILED_PERMANENTLY
                    )
                }
            }
        }

        viewModelScope.launch {
            gameClient?.reconnectAttempt?.collect { attempt ->
                _uiState.update { it.copy(reconnectAttempt = attempt) }
            }
        }

        viewModelScope.launch {
            gameClient?.errors?.collect { error ->
                if (error == "La partida ha sido eliminada por el anfitrión") {
                    _events.emit(GameUiEvent.ShowMessage(error))
                    // Clear local save and state
                    gameRepository?.deleteAllSavedGames()
                    _savedGame.value = null
                    _uiState.update { GameUiState(screen = Screen.HOME, userProfile = it.userProfile) }
                } else {
                    _events.emit(GameUiEvent.ShowError(error))
                }
            }
        }
    }

    fun endTurn() {
        sendPlayerEvent { playerId ->
            EndTurn(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    fun confirmWin(winnerId: PlayerId) {
        if (!isHost) return
        recordGameOver(_uiState.value.gameState?.gameId?.value ?: return, winnerId.value)
        _uiState.update { it.copy(pendingWinnerId = null) }
    }

    fun dismissWinConfirmation() {
        _uiState.update { it.copy(pendingWinnerId = null) }
    }

    private fun recordGameOver(gameId: String, winnerId: String) {
        if (hasRecordedGame) return
        hasRecordedGame = true

        viewModelScope.launch {
            try {
                gameClient?.sendGameOver(gameId, winnerId)
                DLog.i("GameVM", "🏆 Game Over recorded!")
            } catch (e: Exception) {
                DLog.e("GameVM", "Failed to record game over: ${e.message}")
            }
        }
    }


    // ============== Game Log ==============

    private fun addLogEntry(message: String, type: LogType = LogType.INFO) {
        val entry = GameLogEntry(message = message, type = type)
        val currentList = _gameLog.value
        _gameLog.value = (currentList + entry).takeLast(50)
    }
}



// ============== UI State ==============

data class GameUiState(
    val screen: Screen = Screen.HOME,
    val isLoading: Boolean = false,
    val error: String? = null,
    val gameState: GameState? = null,
    val myPlayerId: PlayerId? = null,
    val isHost: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val reconnectAttempt: Int = 0,          // 0 = not reconnecting; >0 = current attempt number
    val isReconnectFailed: Boolean = false, // true when FAILED_PERMANENTLY

    val discoveredGames: List<DiscoveredGame> = emptyList(),
    val isDiscovering: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val userProfile: UserProfile? = null,

    val monsterSearchResults: List<CatalogMonster> = emptyList(),
    val gameHistory: List<GameHistoryItem> = emptyList(),
    val leaderboard: List<LeaderboardEntry> = emptyList(),
    val leaderboardSelf: LeaderboardSelf? = null,
    val pendingWinnerId: PlayerId? = null, // For host to confirm win
    val selectedPlayerId: PlayerId? = null // For viewing player details
) {
    val myPlayer: PlayerState?
        get() = myPlayerId?.let { gameState?.players?.get(it) }

    val selectedPlayer: PlayerState?
        get() = (selectedPlayerId ?: myPlayerId)?.let { gameState?.players?.get(it) }
}

enum class Screen {
    HOME,
    CREATE_GAME,
    JOIN_GAME,
    LOBBY,
    BOARD,
    PLAYER_DETAIL,
    COMBAT,
    CATALOG,
    SETTINGS,
    AUTH, // Login/Register
    PROFILE,
    LEADERBOARD,
    HISTORY
}

sealed class GameUiEvent {
    data class ShowError(val message: String) : GameUiEvent()
    data class ShowSuccess(val message: String) : GameUiEvent()
    data class ShowMessage(val message: String) : GameUiEvent()
    data object PlaySound : GameUiEvent()
    data object Reconnected : GameUiEvent()  // Fired when RECONNECTING → CONNECTED transition
}

// Response from server for available games
@kotlinx.serialization.Serializable
data class GamesListResponse(
    val type: String,
    val games: List<ServerGame>
)

@kotlinx.serialization.Serializable
data class ServerGame(
    val joinCode: String,
    val hostName: String,
    val playerCount: Int,
    val maxPlayers: Int,
    val createdAt: Long = 0
)

/**
 * Convert technical error messages to user-friendly Spanish messages.
 */
internal fun getFriendlyErrorMessage(error: Throwable?): String {
    if (error is com.munchkin.app.network.ServerErrorException) {
        return when (error.code) {
            com.munchkin.app.network.ErrorCode.INVALID_JOIN_CODE -> "Código de partida inválido."
            com.munchkin.app.network.ErrorCode.GAME_NOT_FOUND -> "La partida ya no existe."
            com.munchkin.app.network.ErrorCode.GAME_FULL -> "La partida está llena."
            com.munchkin.app.network.ErrorCode.GAME_ALREADY_STARTED -> "La partida ya ha comenzado."
            com.munchkin.app.network.ErrorCode.PLAYER_NOT_FOUND -> "Jugador no encontrado."
            com.munchkin.app.network.ErrorCode.UNAUTHORIZED -> "No tienes permiso para unirte a esta partida."
            com.munchkin.app.network.ErrorCode.VALIDATION_FAILED, com.munchkin.app.network.ErrorCode.INVALID_DATA -> "Datos inválidos."
            com.munchkin.app.network.ErrorCode.RATE_LIMITED -> "Demasiados intentos. Espera unos segundos."
            com.munchkin.app.network.ErrorCode.FORBIDDEN, com.munchkin.app.network.ErrorCode.PERMISSION_DENIED -> "Acción no permitida."
            com.munchkin.app.network.ErrorCode.EMAIL_EXISTS -> "Ese email ya está registrado."
            com.munchkin.app.network.ErrorCode.USERNAME_EXISTS -> "Ese nombre de usuario ya está en uso."
            com.munchkin.app.network.ErrorCode.UPDATE_FAILED -> "No se pudo guardar el perfil. Vuelve a intentarlo."
            com.munchkin.app.network.ErrorCode.AUTH_FAILED -> "Email o contraseña incorrectos."
            com.munchkin.app.network.ErrorCode.COMBAT_ALREADY_ACTIVE -> "Ya hay un combate activo."
            com.munchkin.app.network.ErrorCode.NO_ACTIVE_COMBAT -> "No hay combate activo."
            com.munchkin.app.network.ErrorCode.COMBAT_MONSTER_LIMIT -> "Se alcanzó el límite de monstruos en combate."
            com.munchkin.app.network.ErrorCode.INVALID_HELPER -> "Selección de ayudante inválida."
            com.munchkin.app.network.ErrorCode.COMBAT_BONUS_LIMIT -> "Se alcanzó el límite de bonificaciones."
            else -> "Error del servidor: ${error.message}"
        }
    }
    
    val message = error?.message?.lowercase() ?: ""
    return when {
        "timeout" in message -> "No se pudo conectar. Comprueba tu conexión a internet y vuelve a intentarlo."
        "refused" in message -> "El servidor no está disponible. Inténtalo más tarde."
        "host" in message && "resolve" in message -> "No se encuentra el servidor. Comprueba tu conexión."
        "closed" in message || "reset" in message -> "Se perdió la conexión. Vuelve a intentarlo."
        else -> "Error de conexión. Vuelve a intentarlo."
    }
}
