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
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
    
    private var client: HttpClient? = null
    private var session: WebSocketSession? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    
    private var lastUrl: String? = null
    private var lastJoinCode: String? = null
    private var lastPlayerMeta: PlayerMeta? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _gameState = MutableStateFlow<GameState?>(null)
    val gameState: StateFlow<GameState?> = _gameState.asStateFlow()
    
    private val _myPlayerId = MutableStateFlow<PlayerId?>(null)
    val myPlayerId: StateFlow<PlayerId?> = _myPlayerId.asStateFlow()
    
    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()
    
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
        playerMeta: PlayerMeta
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
            val urlParts = parseWsUrl(serverUrl)
            if (urlParts == null) {
                DLog.e(TAG, "Invalid URL: $serverUrl")
                _connectionState.value = ConnectionState.DISCONNECTED
                return@withContext Result.failure(Exception("URL inválida: $serverUrl"))
            }
            
            val (host, port, path) = urlParts
            DLog.i(TAG, "Connecting to $host:$port$path")
            
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            // Use Deferred to signal result while keeping connection open
            val resultDeferred = kotlinx.coroutines.CompletableDeferred<Result<GameState>>()
            
            // Launch the WebSocket session in background - it will stay open
            scope?.launch {
                try {
                    client!!.webSocket(host = host, port = port, path = path ?: "/") {
                        session = this
                        DLog.i(TAG, "Connected, sending CreateGame...")
                        
                        // Build JSON manually to avoid serialization issues
                        val createMsgJson = """
                            {
                                "type": "CreateGameMessage",
                                "playerMeta": {
                                    "playerId": "${playerMeta.playerId.value}",
                                    "name": "${playerMeta.name}",
                                    "avatarId": ${playerMeta.avatarId},
                                    "gender": "${playerMeta.gender.name}",
                                    "userId": ${if (playerMeta.userId != null) "\"${playerMeta.userId}\"" else "null"}
                                }
                            }
                        """.trimIndent()
                        send(createMsgJson)
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
            val urlParts = parseWsUrl(wsUrl)
            if (urlParts == null) {
                DLog.e(TAG, "Invalid URL format: $wsUrl")
                Log.e(TAG, "Invalid URL format: $wsUrl")
                _connectionState.value = ConnectionState.DISCONNECTED
                return@withContext Result.failure(Exception("URL inválida: $wsUrl"))
            }
            
            val (host, port, path) = urlParts
            DLog.i(TAG, "Parsed -> $host:$port$path")
            Log.i(TAG, "Parsed URL -> host=$host, port=$port, path=$path")
            
            // Connect
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            // Use Deferred to signal result while keeping connection open
            val resultDeferred = kotlinx.coroutines.CompletableDeferred<Result<GameState>>()
            
            // Launch the WebSocket session in background - it will stay open
            scope?.launch {
                try {
                    DLog.i(TAG, "Opening WebSocket...")
                    Log.i(TAG, "Opening WebSocket connection...")
                    client!!.webSocket(host = host, port = port, path = path) {
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
                Result.success(Unit)
            }
            is StateSnapshotMessage -> {
                _gameState.value = message.gameState
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
                        handleMessage(message)
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
            is HandoverInitMessage -> {
                handleHandover(message)
            }
            else -> {
                Log.d(TAG, "Unhandled message type: ${message::class.simpleName}")
            }
        }
    }
    
    /**
     * Apply an event to local game state.
     */
    private fun applyEvent(event: GameEvent) {
        val currentState = _gameState.value ?: return
        
        // Create a local game engine to apply the event
        val tempEngine = GameEngine()
        tempEngine.loadState(currentState)
        tempEngine.processEvent(event)
        
        _gameState.value = tempEngine.gameState.value
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
     * Attempt to reconnect with backoff.
     */
    private suspend fun attemptReconnect() {
        val url = lastUrl ?: return
        val code = lastJoinCode ?: return
        val meta = lastPlayerMeta ?: return
        
        reconnectJob = scope?.launch {
            var attempts = 0
            
            while (attempts < MAX_RECONNECT_ATTEMPTS && isActive) {
                delay(RECONNECT_DELAY_MS * (attempts + 1))
                
                Log.i(TAG, "Reconnect attempt ${attempts + 1}")
                
                val result = connect(url, code, meta)
                if (result.isSuccess) {
                    Log.i(TAG, "Reconnected successfully")
                    return@launch
                }
                
                attempts++
            }
            
            Log.e(TAG, "Failed to reconnect after $MAX_RECONNECT_ATTEMPTS attempts")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }
    
    /**
     * Handle handover to new host.
     */
    private suspend fun handleHandover(message: HandoverInitMessage) {
        _connectionState.value = ConnectionState.HANDOVER
        
        // Disconnect from current host
        disconnect()
        
        // Connect to new host
        lastUrl = message.wsUrl
        lastPlayerMeta?.let { meta ->
            lastJoinCode?.let { code ->
                connect(message.wsUrl, code, meta)
            }
        }
    }
    
    /**
     * Send an event request to the server.
     */
    suspend fun sendEvent(event: GameEvent): Result<Unit> {
        val currentSession = session ?: return Result.failure(Exception("No conectado"))
        
        return try {
            val message = EventRequestMessage(event)
            currentSession.send(json.encodeToString<WsMessage>(message))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send event", e)
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
        
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    // ============== Auth Methods ==============

    suspend fun register(
        serverUrl: String,
        username: String,
        email: String,
        password: String
    ): Result<UserProfile> {
        // Auto-generate dummy email if empty (Backward compatibility with older servers)
        val finalEmail = if (email.isBlank()) {
            val sanitized = username.lowercase().replace(Regex("[^a-z0-9]"), "")
            "$sanitized@munchkin.local"
        } else {
            email
        }
        val msg = RegisterMessage(username, finalEmail, password, 0)
        return performAuth(serverUrl, msg)
    }

    suspend fun login(
        serverUrl: String,
        email: String,
        password: String
    ): Result<UserProfile> {
        val msg = LoginMessage(email, password)
        return performAuth(serverUrl, msg)
    }

    private suspend fun performAuth(
        serverUrl: String,
        message: WsMessage
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val urlParts = parseWsUrl(serverUrl) ?: return@withContext Result.failure(Exception("URL inválida"))
            val (host, port, _) = urlParts
            
            DLog.i(TAG, "Auth: Connecting to $host:$port...")
            
            // Temporary client for auth
            val authClient = HttpClient(CIO) { install(WebSockets) }
            
            var result: Result<UserProfile>? = null
            
            authClient.webSocket(host = host, port = port, path = "/") {
                // Send auth message - Encoded as WsMessage to preserve "type" field
                val finalJson = json.encodeToString<WsMessage>(message)
                DLog.i(TAG, "Sending auth: $finalJson")
                send(finalJson)
                
                // Wait for response
                try {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        DLog.i(TAG, "Auth response: $text")
                        
                        val response = json.decodeFromString<WsMessage>(text)
                        if (response is AuthSuccessMessage) {
                            result = Result.success(response.user)
                        } else if (response is ErrorMessage) {
                            result = Result.failure(Exception(response.message))
                        }
                    }
                } catch (e: Exception) {
                    DLog.e(TAG, "Auth receive error: ${e.message}")
                    result = Result.failure(e)
                }
                close()
            }
            authClient.close()
            
            result ?: Result.failure(Exception("No response from server"))
            
        } catch (e: Exception) {
            DLog.e(TAG, "Auth error: ${e.message}")
            Result.failure(e)
        }
    }

    // ============== Catalog Methods ==============

    suspend fun searchMonsters(
        serverUrl: String,
        query: String
    ): Result<List<CatalogMonster>> = withContext(Dispatchers.IO) {
        val msg = CatalogSearchRequest(query)
        val response = sendCatalogRequest(serverUrl, msg)
        
        response.map {
            if (it is CatalogSearchResult) it.results else emptyList()
        }
    }

    suspend fun addMonsterToCatalog(
        serverUrl: String,
        monster: CatalogMonster,
        userId: String
    ): Result<CatalogMonster> = withContext(Dispatchers.IO) {
        val msg = CatalogAddRequest(monster, userId)
        val response = sendCatalogRequest(serverUrl, msg)

        response.map {
            if (it is CatalogAddSuccess) it.monster else monster
        }
    }

    private suspend fun sendCatalogRequest(
        serverUrl: String,
        message: WsMessage
    ): Result<WsMessage> = withContext(Dispatchers.IO) {
        try {
            val urlParts = parseWsUrl(serverUrl) ?: return@withContext Result.failure(Exception("URL inválida"))
            val (host, port, _) = urlParts
            
            // Temporary client
            val client = HttpClient(CIO) { install(WebSockets) }
            var result: Result<WsMessage>? = null
            
            client.webSocket(host = host, port = port, path = "/") {
                val jsonStr = json.encodeToString<WsMessage>(message)
                send(jsonStr)
                
                try {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val response = json.decodeFromString<WsMessage>(text)
                        
                        if (response is ErrorMessage) {
                            result = Result.failure(Exception(response.message))
                        } else {
                            result = Result.success(response)
                        }
                    }
                } catch (e: Exception) {
                    result = Result.failure(e)
                }
                close()
            }
            client.close()
            
            result ?: Result.failure(Exception("Sin respuesta del catálogo"))
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============== History Methods ==============

    suspend fun sendGameOver(
        serverUrl: String,
        gameId: String,
        winnerId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val msg = GameOverMessage(gameId, winnerId)
        // Use temp connection or active session?
        // Since host is likely connected, use session if available?
        // But session logic is tied to connect() flow.
        // It's safer to use the dedicated "sendCatalogRequest" style temp connection for these "one-off" events if we want reliable ack,
        // OR reuse session if we are sure we are connected.
        // However, GAME_OVER usually happens when still connected.
        // Let's use sendCatalogRequest style for robustness or if connection drops.
        // Actually, if we use temp connection, server handles it fine (stateless handler).
        
        sendOneOffRequest(serverUrl, msg).map { }
    }

    suspend fun sendEndTurn(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            session?.send(Json.encodeToString(WsMessage.serializer(), EndTurnMessage))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getHistory(
        serverUrl: String,
        userId: String
    ): Result<List<GameHistoryItem>> = withContext(Dispatchers.IO) {
        val request = GetHistoryRequest(userId)
        sendOneOffRequest(serverUrl, request).map { response ->
            if (response is HistoryResult) {
                response.games
            } else {
                emptyList()
            }
        }
    }

    suspend fun getLeaderboard(
        serverUrl: String
    ): Result<List<LeaderboardEntry>> = withContext(Dispatchers.IO) {
        sendOneOffRequest(serverUrl, GetLeaderboardRequest).map { response ->
            if (response is LeaderboardResult) {
                response.leaderboard
            } else {
                emptyList()
            }
        }
    }

    /**
     * Helper for "One Off" requests (connect, send, receive, close).
     */
    private suspend fun sendOneOffRequest(
        serverUrl: String,
        message: WsMessage
    ): Result<WsMessage> = sendCatalogRequest(serverUrl, message)
    private fun parseWsUrl(url: String): Triple<String, Int, String>? {
        val regex = Regex("""ws://([^:]+):(\d+)(/.*)?""")
        val match = regex.find(url) ?: return null
        
        val host = match.groupValues[1]
        val port = match.groupValues[2].toIntOrNull() ?: return null
        val path = match.groupValues.getOrNull(3)?.ifEmpty { "/game" } ?: "/game"
        
        return Triple(host, port, path)
    }
}
