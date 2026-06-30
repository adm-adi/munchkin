package com.munchkin.app.viewmodel

import androidx.lifecycle.viewModelScope
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.PlayerId
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.models.GamesListResponse
import com.munchkin.app.network.models.PlayerMeta
import com.munchkin.app.viewmodel.models.DiscoveredGame
import com.munchkin.app.viewmodel.models.Gender
import com.munchkin.app.core.events.GameStart
import com.munchkin.app.util.DLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

fun GameViewModel.resumeSavedGame() {
    val saved = _savedGame.value ?: return
    viewModelScope.launch {
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null
            )
        }

        myPlayerId = saved.myPlayerId
        isHost = saved.isHost

        try {
            val player = saved.gameState.players[saved.myPlayerId]
            val playerMeta = PlayerMeta(
                playerId = saved.myPlayerId,
                name = player?.name ?: "Player",
                avatarId = player?.avatarId ?: 0,
                gender = player?.gender ?: Gender.M,
                userId = _uiState.value.userProfile?.id
            )

            val client = GameClient()
            val result = client.connect(
                GameViewModel.SERVER_URL,
                saved.gameState.joinCode,
                playerMeta,
                reconnectToken = sessionManager?.getReconnectToken(saved.gameState.joinCode),
                authToken = sessionManager?.getAuthToken()
            )

            if (result.isFailure) {
                val friendlyError = getFriendlyErrorMessage(result.exceptionOrNull())
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = friendlyError
                    )
                }
                return@launch
            }

            gameClient = client
            val gameState = result.getOrNull()
            val actualPlayerId = client.currentPlayerId ?: saved.myPlayerId
            val actualIsHost = (gameState?.hostId ?: saved.gameState.hostId) == actualPlayerId
            myPlayerId = actualPlayerId
            isHost = actualIsHost
            client.currentReconnectToken?.let {
                sessionManager?.saveReconnectToken((gameState ?: saved.gameState).joinCode, it)
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    screen = if ((gameState?.phase ?: saved.gameState.phase) == GamePhase.LOBBY) {
                        Screen.LOBBY
                    } else if (gameState?.combat != null) {
                        Screen.COMBAT
                    } else {
                        Screen.BOARD
                    },
                    gameState = gameState ?: saved.gameState,
                    myPlayerId = actualPlayerId,
                    isHost = actualIsHost
                )
            }

            observeClientState()

        } catch (e: Exception) {
            val friendlyError = getFriendlyErrorMessage(e)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = friendlyError
                )
            }
        }
    }
}

fun GameViewModel.checkReconnection() {
    val client = gameClient
    val saved = _savedGame.value

    if (saved != null && (client == null || !client.isConnected() ||
            client.connectionState.value == com.munchkin.app.network.ConnectionState.FAILED_PERMANENTLY)) {
        android.util.Log.d("GameViewModel", "Auto-reconnecting to saved game...")
        resumeSavedGame()
    }
}

fun GameViewModel.retryReconnect() {
    _uiState.update { it.copy(isReconnectFailed = false) }
    resumeSavedGame()
}

fun GameViewModel.deleteSavedGame() {
    val saved = _savedGame.value ?: return

    viewModelScope.launch {
        if (saved.isHost) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val client = GameClient()

                val playerMeta = PlayerMeta(
                    playerId = saved.myPlayerId,
                    name = "Host",
                    avatarId = 0,
                    gender = Gender.M,
                    userId = _uiState.value.userProfile?.id
                )

                val connectResult = client.connect(
                    GameViewModel.SERVER_URL,
                    saved.gameState.joinCode,
                    playerMeta,
                    reconnectToken = sessionManager?.getReconnectToken(saved.gameState.joinCode),
                    authToken = sessionManager?.getAuthToken()
                )
                if (connectResult.isSuccess) {
                    client.sendDeleteGame()
                    delay(500)
                    client.disconnect()
                }
            } catch (e: Exception) {
                DLog.e("GameVM", "Failed to delete on server: ${e.message}")
            }
        }

        gameRepository?.deleteAllSavedGames()
        _savedGame.value = null
        _uiState.update { it.copy(isLoading = false, error = null) }
    }
}

fun GameViewModel.createGame(name: String, avatarId: Int, gender: Gender, timerSeconds: Int = 0, superMunchkin: Boolean = false) {
    android.util.Log.d("GameViewModel", "createGame called: name=$name, avatarId=$avatarId, gender=$gender, timer=$timerSeconds, superMunchkin=$superMunchkin")
    viewModelScope.launch {
        try {
            android.util.Log.d("GameViewModel", "Setting loading state...")
            _uiState.update { it.copy(isLoading = true, error = null) }

            val playerId = PlayerId(UUID.randomUUID().toString())
            myPlayerId = playerId
            isHost = true
            android.util.Log.d("GameViewModel", "Generated playerId: ${playerId.value}")

            val user = _uiState.value.userProfile
            val playerMeta = PlayerMeta(
                playerId = playerId,
                name = name.trim(),
                avatarId = avatarId,
                gender = gender,
                userId = user?.id
            )

            DLog.i("GameVM", "🎮 Creating game on Hetzner...")
            DLog.i("GameVM", "📡 Server: ${GameViewModel.SERVER_URL}")

            val client = GameClient()
            gameClient = client

            val result = client.createGame(
                GameViewModel.SERVER_URL,
                playerMeta,
                superMunchkin,
                timerSeconds,
                authToken = sessionManager?.getAuthToken()
            )

            if (result.isFailure) {
                val error = result.exceptionOrNull()?.message ?: "Error desconocido"
                DLog.e("GameVM", "❌ Create failed: $error")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error
                    )
                }
                return@launch
            }

            val gameState = result.getOrNull() ?: return@launch
            val actualPlayerId = client.currentPlayerId ?: playerId
            myPlayerId = actualPlayerId
            client.currentReconnectToken?.let {
                sessionManager?.saveReconnectToken(gameState.joinCode, it)
            }
            sessionManager?.savePlayerId(gameState.joinCode, actualPlayerId.value)

            DLog.i("GameVM", "✅ Game created!")
            DLog.i("GameVM", "🔑 Code: ${gameState.joinCode}")

            android.util.Log.d("GameViewModel", "Game created with joinCode: ${gameState.joinCode}")

            gameRepository?.saveGame(gameState, actualPlayerId, true)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    screen = Screen.LOBBY,
                    gameState = gameState,
                    myPlayerId = actualPlayerId,
                    isHost = true
                )
            }
            android.util.Log.d("GameViewModel", "State updated, navigating to LOBBY")

            observeClientState()

        } catch (e: Exception) {
            android.util.Log.e("GameViewModel", "createGame exception", e)
            _uiState.update {
                it.copy(isLoading = false, error = "Error: ${e.message}")
            }
        }
    }
}

fun GameViewModel.startGame() {
    if (!isHost) return
    sendPlayerEvent { playerId ->
        GameStart(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis()
        )
    }
}

fun GameViewModel.swapPlayers(player1: PlayerId, player2: PlayerId) {
    if (!isHost) return
    viewModelScope.launch {
        try {
            gameClient?.sendSwapPlayers(player1, player2)
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Error al reordenar: ${e.message}") }
        }
    }
}

fun GameViewModel.joinGame(
    wsUrl: String,
    joinCode: String,
    name: String,
    avatarId: Int,
    gender: Gender
) {
    viewModelScope.launch {
        try {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val existingPlayerIdStr = sessionManager?.getPlayerId(joinCode)
            val playerId = if (existingPlayerIdStr != null) {
                PlayerId(existingPlayerIdStr)
            } else {
                PlayerId(UUID.randomUUID().toString())
            }
            myPlayerId = playerId
            isHost = false

            val user = _uiState.value.userProfile
            val playerMeta = PlayerMeta(
                playerId = playerId,
                name = name.trim(),
                avatarId = avatarId,
                gender = gender,
                userId = user?.id
            )

            val client = GameClient()
            val result = client.connect(
                wsUrl,
                joinCode,
                playerMeta,
                reconnectToken = sessionManager?.getReconnectToken(joinCode),
                authToken = sessionManager?.getAuthToken()
            )

            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Error al conectar: ${result.exceptionOrNull()?.message}"
                    )
                }
                return@launch
            }

            gameClient = client

            val gameState = result.getOrNull()
            val actualPlayerId = client.currentPlayerId ?: playerId
            myPlayerId = actualPlayerId
            client.currentReconnectToken?.let {
                sessionManager?.saveReconnectToken(gameState?.joinCode ?: joinCode, it)
            }
            sessionManager?.savePlayerId(gameState?.joinCode ?: joinCode, actualPlayerId.value)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    screen = if (gameState?.phase == GamePhase.LOBBY) {
                        Screen.LOBBY
                    } else if (gameState?.combat != null) {
                        Screen.COMBAT
                    } else {
                         Screen.BOARD
                    },
                    gameState = gameState,
                    myPlayerId = actualPlayerId,
                    isHost = false
                )
            }

            gameState?.let { state ->
                gameRepository?.saveGame(state, actualPlayerId, isHost = false)
            }

            observeClientState()

        } catch (e: Exception) {
            _uiState.update {
                it.copy(isLoading = false, error = "Error: ${e.message}")
            }
        }
    }
}

fun GameViewModel.startDiscovery() {
    viewModelScope.launch(Dispatchers.IO) {
        _uiState.update { it.copy(isDiscovering = true, discoveredGames = emptyList()) }
        try {
            val client = HttpClient(CIO) {
                install(WebSockets)
            }
            client.webSocket(GameViewModel.SERVER_URL) {
                send("{\"type\": \"LIST_GAMES\"}")
                val frame = incoming.receive()
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    DLog.i("GameViewModel", "Games list response: $text")
                    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val response = json.decodeFromString<GamesListResponse>(text)
                    val discovered = response.games.map { game ->
                        DiscoveredGame(
                            hostName = game.hostName,
                            joinCode = game.joinCode,
                            playerCount = game.playerCount,
                            maxPlayers = game.maxPlayers,
                            wsUrl = GameViewModel.SERVER_URL
                        )
                    }
                    _uiState.update { it.copy(discoveredGames = discovered) }
                }
            }
            client.close()
        } catch (e: Exception) {
            DLog.e("GameViewModel", "Discovery failed: ${e.message}")
        } finally {
            _uiState.update { it.copy(isDiscovering = false) }
        }
    }
}

fun GameViewModel.joinDiscoveredGame(
    game: DiscoveredGame,
    name: String,
    avatarId: Int,
    gender: Gender
) {
    joinGame(GameViewModel.SERVER_URL, game.joinCode, name, avatarId, gender)
}
