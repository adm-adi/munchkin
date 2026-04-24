package com.munchkin.app.network

import android.util.Log
import com.munchkin.app.core.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.munchkin.app.ui.components.DebugLogManager as DLog

/**
 * WebSocket client for joining a game as a non-host player.
 */
class GameClient {
    companion object {
        private const val TAG = "GameClient"
        private const val RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L  // cap at 30 seconds
        private const val MAX_RECONNECT_ATTEMPTS = 15       // was 5
    }
    
    private var client: HttpClient? = null
    private var session: WebSocketSession? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    
    private var lastUrl: String? = null
    private var lastJoinCode: String? = null
    private var lastPlayerMeta: PlayerMeta? = null

    // Persistent engine — reused across events to avoid allocating a new instance per message
    private var gameEngine: GameEngine? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _gameState = MutableStateFlow<GameState?>(null)
    val gameState: StateFlow<GameState?> = _gameState.asStateFlow()
    
    private val _myPlayerId = MutableStateFlow<PlayerId?>(null)
    val myPlayerId: StateFlow<PlayerId?> = _myPlayerId.asStateFlow()
    
    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _gameDeleted = MutableSharedFlow<String>()
    val gameDeleted: SharedFlow<String> = _gameDeleted.asSharedFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
    
    /**
     * Create a new game on the remote server.
     */
    suspend fun createGame(
        serverUrl: String,
        playerMeta: PlayerMeta,
        superMunchkin: Boolean = false,
        turnTimerSeconds: Int = 0
    ): Result<GameState> = withContext(Dispatchers.IO) {
        try {
            DLog.i(TAG, "Creating game on $serverUrl")
            _connectionState.value = ConnectionState.CONNECTING
            
            // Store for reconnection
            lastUrl = serverUrl
            lastPlayerMeta = playerMeta
            
            // Create HTTP client
            client = HttpClient(CIO) {
                install(WebSockets) {
                    pingInterval = 15_000
                }
            }
            
            // Parse URL
            val endpoint = WsEndpointParser.parse(serverUrl)
            if (endpoint == null) {
                DLog.e(TAG, "Invalid URL: $serverUrl")
                _connectionState.value = ConnectionState.DISCONNECTED
                return@withContext Result.failure(Exception("Invalid URL: $serverUrl"))
            }
            
            if (!endpoint.isSecure) {
                DLog.w(TAG, "Connecting over unencrypted ws://. Use wss:// for production servers.")
            }
            DLog.i(TAG, "Connecting to ${endpoint.host}:${endpoint.port}${endpoint.path}")

            scope?.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            // Use Deferred to signal result while keeping connection open
            val resultDeferred = kotlinx.coroutines.CompletableDeferred<Result<GameState>>()
            
            // Launch the WebSocket session in background - it will stay open
            scope?.launch {
                try {
                    client!!.webSocket(urlString = endpoint.urlString) {
                        session = this
                        DLog.i(TAG, "Connected, sending CreateGame...")
                        val createMessage = CreateGameRequest(
                            playerMeta = playerMeta,
                            superMunchkin = superMunchkin,
                            turnTimerSeconds = turnTimerSeconds
                        )
                        send(json.encodeToString<WsMessage>(createMessage))
                        DLog.i(TAG, "Waiting for welcome...")
                        
                        // Wait for welcome
                        val welcomeResult = waitForWelcome()
                        
                        if (welcomeResult.isFailure) {
                            DLog.e(TAG, "Welcome failed: ${welcomeResult.exceptionOrNull()?.message}")
                            _connectionState.value = ConnectionState.DISCONNECTED
                            resultDeferred.complete(Result.failure(welcomeResult.exceptionOrNull() ?: Exception("Welcome failed")))
                            return@webSocket
                        }
                        
                        DLog.i(TAG, "✅ Welcome received!")
                        _connectionState.value = ConnectionState.CONNECTED
                        lastJoinCode = _gameState.value?.joinCode
                        
                        // Signal success to caller
                        resultDeferred.complete(Result.success(_gameState.value!!))
                        
                        // NOW stay in this block handling messages - keeps connection open!
                        handleIncomingMessages()
                    }
                } catch (e: Exception) {
                    DLog.e(TAG, "Connection error: ${e.message}")
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(Result.failure(e))
                    }
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
            
            // Wait for result (but WebSocket stays open in background)
            resultDeferred.await()
            
        } catch (e: Exception) {
            DLog.e(TAG, "CreateGame failed: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
            Result.failure(e)
        }
    }
    
    /**
     * Connect to a game server.
     */
    suspend fun connect(
        wsUrl: String,
        joinCode: String,
        playerMeta: PlayerMeta
    ): Result<GameState> = withContext(Dispatchers.IO) {
        try {
            DLog.i(TAG, "Connecting to $wsUrl with code $joinCode")
            Log.i(TAG, "Connecting to $wsUrl with code $joinCode")
            _connectionState.value = ConnectionState.CONNECTING
            
            // Store for reconnection
            lastUrl = wsUrl
            lastJoinCode = joinCode
            lastPlayerMeta = playerMeta
            
            // Create HTTP client with WebSocket support
            client = HttpClient(CIO) {
                install(WebSockets) {
                    pingInterval = 15_000
                }
            }
            
            // Parse URL
            val endpoint = WsEndpointParser.parse(wsUrl)
            if (endpoint == null) {
                DLog.e(TAG, "Invalid URL format: $wsUrl")
                Log.e(TAG, "Invalid URL format: $wsUrl")
                _connectionState.value = ConnectionState.DISCONNECTED
                return@withContext Result.failure(Exception("Invalid URL: $wsUrl"))
            }
            
            if (!endpoint.isSecure) {
                DLog.w(TAG, "Connecting over unencrypted ws://. Use wss:// for production servers.")
            }
            DLog.i(TAG, "Parsed -> ${endpoint.host}:${endpoint.port}${endpoint.path}")
            Log.i(TAG, "Parsed URL -> host=${endpoint.host}, port=${endpoint.port}, path=${endpoint.path}")

            // Connect
            scope?.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            // Use Deferred to signal result while keeping connection open
            val resultDeferred = kotlinx.coroutines.CompletableDeferred<Result<GameState>>()
            
            // Launch the WebSocket session in background - it will stay open
            scope?.launch {
                try {
                    DLog.i(TAG, "Opening WebSocket...")
                    Log.i(TAG, "Opening WebSocket connection...")
                    client!!.webSocket(urlString = endpoint.urlString) {
                        session = this
                        DLog.i(TAG, "WS connected, sending hello")
                        Log.i(TAG, "WebSocket connected, sending hello...")
                        
                        // Send hello
                        val hello = HelloMessage(
                            gameId = "",  // Will be validated by join code
                            joinCode = joinCode,
                            playerMeta = playerMeta
                        )
                        send(json.encodeToString<WsMessage>(hello))
                        Log.i(TAG, "Hello sent, waiting for welcome...")
                        
                        // Wait for welcome or error
                        val welcomeResult = waitForWelcome()
                        
                        if (welcomeResult.isFailure) {
                            Log.e(TAG, "Welcome failed: ${welcomeResult.exceptionOrNull()?.message}")
                            _connectionState.value = ConnectionState.DISCONNECTED
                            resultDeferred.complete(Result.failure(welcomeResult.exceptionOrNull() ?: Exception("Welcome failed")))
                            return@webSocket
                        }
                        
                        Log.i(TAG, "Welcome received! GameState set.")
                        _connectionState.value = ConnectionState.CONNECTED
                        
                        // Signal success to caller
                        val state = _gameState.value
                        if (state != null) {
                            resultDeferred.complete(Result.success(state))
                        } else {
                            resultDeferred.complete(Result.failure(Exception("No game state")))
                            return@webSocket
                        }
                        
                        // NOW stay in this block handling messages - keeps connection open!
                        handleIncomingMessages()
                    }
                } catch (e: Exception) {
                    DLog.e(TAG, "WS error: ${e.message}")
                    Log.e(TAG, "WebSocket exception: ${e.message}", e)
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(Result.failure(e))
                    }
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
            
            // Wait for result (but WebSocket stays open in background)
            resultDeferred.await()
            
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed with exception", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            Result.failure(e)
        }
    }
    
    /**
     * Wait for welcome message after hello.
     */
    private suspend fun WebSocketSession.waitForWelcome(): Result<Unit> {
        val frame = incoming.receive()
        
        if (frame !is Frame.Text) {
            return Result.failure(Exception("Respuesta inesperada"))
        }
        
        val message = try {
            json.decodeFromString<WsMessage>(frame.readText())
        } catch (e: Exception) {
            return Result.failure(Exception("Error al parsear respuesta"))
        }
        
        return when (message) {
            is WelcomeMessage -> {
                _gameState.value = message.gameState
                _myPlayerId.value = message.yourPlayerId
                // (Re-)initialize the persistent engine on every welcome/reconnect
                val engine = GameEngine()
                engine.loadState(message.gameState)
                gameEngine = engine
                Result.success(Unit)
            }
            is StateSnapshotMessage -> {
                _gameState.value = message.gameState
                // Re-sync engine to authoritative snapshot
                gameEngine?.loadState(message.gameState) ?: run {
                    val engine = GameEngine()
                    engine.loadState(message.gameState)
                    gameEngine = engine
                }
                Result.success(Unit)
            }
            is ErrorMessage -> {
                _errors.emit(message.message)
                Result.failure(Exception(message.message))
            }
            else -> Result.failure(Exception("Respuesta inesperada"))
        }
    }
    
    /**
     * Handle incoming messages in a loop.
     */
    private suspend fun WebSocketSession.handleIncomingMessages() {
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        DLog.i(TAG, "📩 Received message: ${text.take(100)}...")
                        val message = try {
                            json.decodeFromString<WsMessage>(text)
                        } catch (e: Exception) {
                            DLog.e(TAG, "Failed to parse: ${e.message}")
                            Log.e(TAG, "Failed to parse message", e)
                            continue
                        }
                        
                        DLog.i(TAG, "✅ Parsed as: ${message::class.simpleName}")
                        try {
                            handleMessage(message)
                        } catch (e: Exception) {
                            DLog.e(TAG, "Error handling message: ${e.message}")
                            Log.e(TAG, "Error handling message", e)
                        }
                    }
                    is Frame.Close -> {
                        Log.i(TAG, "Server closed connection")
                        break
                    }
                    else -> { /* Ignore */ }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Message loop error", e)
        } finally {
            handleDisconnect()
        }
    }
    
    /**
     * Handle a received message.
     */
    private suspend fun handleMessage(message: WsMessage) {
        when (message) {
            is StateSnapshotMessage -> {
                _gameState.value = message.gameState
                // Keep persistent engine in sync with server snapshots
                gameEngine?.loadState(message.gameState) ?: run {
                    val engine = GameEngine()
                    engine.loadState(message.gameState)
                    gameEngine = engine
                }
            }
            is EventBroadcastMessage -> {
                // Apply event to local state
                applyEvent(message.event)
            }
            is PlayerStatusMessage -> {
                updatePlayerStatus(message.playerId, message.isConnected)
            }
            is ErrorMessage -> {
                _errors.emit(message.message)
            }
            is PongMessage -> {
                // Keepalive response
            }
            is GameOverRecordedMessage -> {
                // The authoritative final snapshot is handled separately.
            }
            is GameDeletedMessage -> {
                _gameDeleted.emit(message.reason)
                disconnect()
            }
            else -> {
                Log.d(TAG, "Unhandled message type: ${message::class.simpleName}")
            }
        }
    }
    
    /**
     * Apply an event to local game state using the persistent engine.
     * Avoids allocating a new GameEngine instance on every incoming broadcast.
     */
    private fun applyEvent(event: GameEvent) {
        val engine = gameEngine ?: run {
            // Edge case: event arrives before WELCOME (e.g. during reconnect race).
            // Bootstrap from current state and promote as the persistent engine.
            val s = _gameState.value ?: return
            GameEngine().also { it.loadState(s); gameEngine = it }
        }
        engine.processEvent(event)
        _gameState.value = engine.gameState.value
    }
    
    /**
     * Update player connection status in local state.
     */
    private fun updatePlayerStatus(playerId: PlayerId, isConnected: Boolean) {
        val currentState = _gameState.value ?: return
        val player = currentState.players[playerId] ?: return
        
        val updatedPlayer = player.copy(isConnected = isConnected)
        _gameState.value = currentState.copy(
            players = currentState.players + (playerId to updatedPlayer)
        )
    }
    
    /**
     * Handle disconnection.
     */
    private suspend fun handleDisconnect() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return
        
        _connectionState.value = ConnectionState.RECONNECTING
        attemptReconnect()
    }
    
    /**
     * Attempt to reconnect with exponential backoff (1s, 2s, 4s, 8s, 16s, 30s cap).
     * Emits attempt counter so the UI can show progress.
     * Sets FAILED_PERMANENTLY after all attempts are exhausted.
     */
    private suspend fun attemptReconnect() {
        val url = lastUrl ?: return
        val code = lastJoinCode ?: return
        val meta = lastPlayerMeta ?: return

        reconnectJob = scope?.launch {
            var attempts = 0

            while (attempts < MAX_RECONNECT_ATTEMPTS && isActive) {
                // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s, 30s, ...
                val delayMs = minOf(RECONNECT_DELAY_MS * (1L shl attempts), MAX_RECONNECT_DELAY_MS)
                _reconnectAttempt.value = attempts + 1
                Log.i(TAG, "Reconnect attempt ${attempts + 1}/$MAX_RECONNECT_ATTEMPTS (delay: ${delayMs}ms)")
                delay(delayMs)

                val result = connect(url, code, meta)
                if (result.isSuccess) {
                    Log.i(TAG, "✅ Reconnected successfully on attempt ${attempts + 1}")
                    _reconnectAttempt.value = 0
                    return@launch
                }

                attempts++
            }

            Log.e(TAG, "❌ Failed to reconnect after $MAX_RECONNECT_ATTEMPTS attempts")
            _reconnectAttempt.value = 0
            _connectionState.value = ConnectionState.FAILED_PERMANENTLY
        }
    }
    
    /**
     * Send an event request to the server.
     */
    suspend fun sendEvent(event: GameEvent): Result<Unit> {
        val currentSession = session ?: return Result.failure(Exception("No conectado"))
        
        return try {
            val msg = EventRequestMessage(event)
            val jsonStr = json.encodeToString<WsMessage>(msg)
            currentSession.send(jsonStr)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun kickPlayer(targetPlayerId: PlayerId): Result<Unit> {
        val currentSession = session ?: return Result.failure(Exception("No conectado"))
        return try {
            val msg = KickPlayerMessage(targetPlayerId)
            val jsonStr = json.encodeToString<WsMessage>(msg)
            currentSession.send(jsonStr)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendDeleteGame(): Result<Unit> {
        val currentSession = session ?: return Result.failure(Exception("No conectado"))
        
        return try {
            val msg = DeleteGameMessage()
            val jsonStr = json.encodeToString<WsMessage>(msg)
            currentSession.send(jsonStr)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    
    /**
     * Disconnect from the server.
     */
    suspend fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        
        try {
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnecting"))
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session", e)
        }
        
        session = null
        client?.close()
        client = null
        scope?.cancel()
        scope = null
        gameEngine = null         // Reset so a fresh engine is created on next WELCOME
        _reconnectAttempt.value = 0

        _connectionState.value = ConnectionState.DISCONNECTED
    }
    

    suspend fun sendGameOver(
        gameId: String,
        winnerId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val currentSession = session ?: return@withContext Result.failure(Exception("No conectado"))
        try {
            val msg = GameOverMessage(gameId, winnerId)
            currentSession.send(json.encodeToString<WsMessage>(msg))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendSwapPlayers(player1: PlayerId, player2: PlayerId): Result<Unit> = withContext(Dispatchers.IO) {
        val currentSession = session ?: return@withContext Result.failure(Exception("No conectado"))
        try {
            val msg = SwapPlayers(player1, player2)
            currentSession.send(json.encodeToString<WsMessage>(msg))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



}
