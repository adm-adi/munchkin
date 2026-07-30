package com.munchkin.app.network

import android.util.Log
import com.munchkin.app.core.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.munchkin.app.ui.components.DebugLogManager as DLog

/**
 * Custom exception representing a backend error code.
 */
class ServerErrorException(val code: ErrorCode, message: String) : Exception(message)

/**
 * WebSocket client for joining a game as a non-host player.
 */
class GameClient {
    companion object {
        private const val TAG = "GameClient"
        private const val RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L  // cap at 30 seconds
        private const val MAX_RECONNECT_ATTEMPTS = 15       // was 5

        /**
         * Every server reply is awaited with a timeout. A server that completes the
         * WebSocket handshake but never answers would otherwise leave the caller
         * suspended forever, stranding the UI on "Conectando…" with no way out.
         */
        private const val RESPONSE_TIMEOUT_MS = 15_000L

        /** How often to re-measure the server clock offset. */
        private const val CLOCK_SYNC_INTERVAL_MS = 20_000L
    }

    /** Thrown when the server accepts the connection but does not reply in time. */
    class ServerTimeoutException : Exception("El servidor no respondió a tiempo")

    /**
     * Receives the next frame, or fails instead of suspending indefinitely.
     */
    private suspend fun WebSocketSession.receiveWithTimeout(): Frame =
        withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { incoming.receive() }
            ?: throw ServerTimeoutException()
    
    private var client: HttpClient? = null
    private var session: WebSocketSession? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    
    private var lastUrl: String? = null
    private var lastJoinCode: String? = null
    private var lastPlayerMeta: PlayerMeta? = null
    private var lastReconnectToken: String? = null
    private var lastAuthToken: String? = null

    // Persistent engine — reused across events to avoid allocating a new instance per message
    private var gameEngine: GameEngine? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _gameState = MutableStateFlow<GameState?>(null)
    val gameState: StateFlow<GameState?> = _gameState.asStateFlow()
    
    private val _myPlayerId = MutableStateFlow<PlayerId?>(null)
    val myPlayerId: StateFlow<PlayerId?> = _myPlayerId.asStateFlow()
    
    // Buffered on purpose: with the default (replay = 0, extraBufferCapacity = 0)
    // emit() suspends until a collector receives the value. An ERROR arriving while
    // nothing was collecting — app backgrounded, collector not yet attached — stalled
    // the WebSocket read loop indefinitely. DROP_OLDEST keeps the socket moving even
    // if errors outpace the UI.
    private val _errors = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    /**
     * The seat's current reconnect token, republished every time the server
     * rotates it (which is on every WELCOME, including the ones the internal
     * backoff loop triggers).
     *
     * Exposed as a flow so persistence happens wherever the token changes. It
     * used to be saved only at the explicit join call sites, so after the first
     * automatic reconnect the stored copy was stale and a guest who lost the
     * process could no longer reclaim their seat.
     */
    private val _reconnectToken = MutableStateFlow<String?>(null)
    val reconnectToken: StateFlow<String?> = _reconnectToken.asStateFlow()

    /**
     * Set for the whole of [disconnect] so the read loop's `finally` does not
     * mistake a deliberate teardown for a dropped connection. Without it, closing
     * the session made the loop exit, call handleDisconnect(), and spawn a
     * reconnect loop on a scope that [disconnect] does not own — silently
     * rejoining the game the user had just left.
     */
    @Volatile
    private var isShuttingDown = false

    /** Signals that the host deleted the room. Carries the server's reason. */
    private val _gameDeleted = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val gameDeleted: SharedFlow<String> = _gameDeleted.asSharedFlow()

    /**
     * Estimated difference between the server's clock and this device's, in ms.
     *
     * Deadlines such as `turnEndsAt` are server timestamps. Comparing them against
     * System.currentTimeMillis() made the countdown wrong by however far the phone's
     * clock drifted, while the server still cut the turn on its own schedule.
     * Add this to a local time to get server time.
     */
    private val _serverTimeOffsetMs = MutableStateFlow(0L)
    val serverTimeOffsetMs: StateFlow<Long> = _serverTimeOffsetMs.asStateFlow()

    /** Last measured round-trip time, in ms. */
    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    /** When the outstanding PING was sent, by the local clock. */
    private var pingSentAtMs: Long? = null
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        // Unknown enum values fall back to the property default instead of
        // throwing, so a server that adds an error code cannot break this client.
        coerceInputValues = true
    }

    val currentPlayerId: PlayerId?
        get() = _myPlayerId.value

    val currentReconnectToken: String?
        get() = _reconnectToken.value
    
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
    
    /**
     * Create a new game on the remote server.
     */
    suspend fun createGame(
        serverUrl: String,
        playerMeta: PlayerMeta,
        superMunchkin: Boolean = false,
        turnTimerSeconds: Int = 0,
        authToken: String? = null
    ): Result<GameState> = withContext(Dispatchers.IO) {
        try {
            DLog.i(TAG, "Creating game on $serverUrl")
            beginSession()
            _connectionState.value = ConnectionState.CONNECTING
            
            // Store for reconnection
            lastUrl = serverUrl
            lastPlayerMeta = playerMeta
            lastAuthToken = authToken
            
            // Close any previous engine before replacing it: each CIO client owns a
            // selector and thread pool, so overwriting the field leaked one engine per
            // reconnect attempt (up to MAX_RECONNECT_ATTEMPTS per session).
            client?.close()
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

            scope?.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            // Use Deferred to signal result while keeping connection open
            val resultDeferred = kotlinx.coroutines.CompletableDeferred<Result<GameState>>()
            
            // Launch the WebSocket session in background - it will stay open
            scope?.launch {
                try {
                    client!!.webSocket(urlString = "${"wss".takeIf { serverUrl.startsWith("wss://") } ?: "ws"}://$host:$port$path") {
                        session = this
                        DLog.i(TAG, "Connected, sending CreateGame...")

                        val authResult = authenticateIfNeeded(authToken)
                        if (authResult.isFailure) {
                            _connectionState.value = ConnectionState.DISCONNECTED
                            resultDeferred.complete(Result.failure(authResult.exceptionOrNull() ?: Exception("Auth failed")))
                            return@webSocket
                        }

                        val createRequest = CreateGameRequest(
                            playerMeta = playerMeta,
                            superMunchkin = superMunchkin,
                            turnTimerSeconds = turnTimerSeconds
                        )
                        send(json.encodeToString<WsMessage>(createRequest))
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
        playerMeta: PlayerMeta,
        reconnectToken: String? = null,
        authToken: String? = null
    ): Result<GameState> = withContext(Dispatchers.IO) {
        try {
            DLog.i(TAG, "Connecting to $wsUrl with code $joinCode")
            Log.i(TAG, "Connecting to $wsUrl with code $joinCode")
            beginSession()
            _connectionState.value = ConnectionState.CONNECTING
            
            // Store for reconnection
            lastUrl = wsUrl
            lastJoinCode = joinCode
            lastPlayerMeta = playerMeta
            lastReconnectToken = reconnectToken
            lastAuthToken = authToken
            
            // Close the previous engine first. connect() is called once per reconnect
            // attempt, so overwriting this field without closing leaked a CIO engine
            // (selector + thread pool) on every retry.
            client?.close()
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
            scope?.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            // Use Deferred to signal result while keeping connection open
            val resultDeferred = kotlinx.coroutines.CompletableDeferred<Result<GameState>>()
            
            // Launch the WebSocket session in background - it will stay open
            scope?.launch {
                try {
                    DLog.i(TAG, "Opening WebSocket...")
                    Log.i(TAG, "Opening WebSocket connection...")
                    client!!.webSocket(urlString = "${"wss".takeIf { wsUrl.startsWith("wss://") } ?: "ws"}://$host:$port$path") {
                        session = this
                        DLog.i(TAG, "WS connected, sending hello")
                        Log.i(TAG, "WebSocket connected, sending hello...")

                        val authResult = authenticateIfNeeded(authToken)
                        if (authResult.isFailure) {
                            _connectionState.value = ConnectionState.DISCONNECTED
                            resultDeferred.complete(Result.failure(authResult.exceptionOrNull() ?: Exception("Auth failed")))
                            return@webSocket
                        }
                        
                        // Send hello
                        val hello = HelloMessage(
                            gameId = "",  // Will be validated by join code
                            joinCode = joinCode,
                            playerMeta = playerMeta,
                            reconnectToken = reconnectToken
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
                        
                        // Keep the server clock offset fresh while connected.
                        startClockSync(this)

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
    
    private suspend fun WebSocketSession.authenticateIfNeeded(token: String?): Result<Unit> {
        if (token.isNullOrBlank()) return Result.success(Unit)

        return try {
            send(json.encodeToString<WsMessage>(LoginWithTokenMessage(token)))
            val frame = receiveWithTimeout()
            if (frame !is Frame.Text) {
                return Result.failure(Exception("Respuesta de autenticacion inesperada"))
            }

            when (val message = json.decodeFromString<WsMessage>(frame.readText())) {
                is AuthSuccessMessage -> Result.success(Unit)
                is ErrorMessage -> Result.failure(ServerErrorException(message.code, message.message))
                else -> Result.failure(Exception("Respuesta de autenticacion inesperada"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Wait for welcome message after hello.
     */
    private suspend fun WebSocketSession.waitForWelcome(): Result<Unit> {
        val frame = receiveWithTimeout()
        
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
                lastReconnectToken = message.reconnectToken
                _reconnectToken.value = message.reconnectToken
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
                Result.failure(ServerErrorException(message.code, message.message))
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
                        // Log size only, not content: WELCOME carries a reconnectToken
                        // and AUTH_SUCCESS carries a JWT. The decoded type is logged
                        // just below, which is what is actually useful when debugging.
                        DLog.i(TAG, "📩 Received message (${text.length} chars)")
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
                // Estimate the clock offset, assuming the round trip is symmetric:
                // the server's timestamp corresponds to roughly halfway through.
                val sentAt = pingSentAtMs
                if (sentAt != null) {
                    pingSentAtMs = null
                    val now = System.currentTimeMillis()
                    val roundTrip = (now - sentAt).coerceAtLeast(0L)
                    _latencyMs.value = roundTrip
                    val serverNow = message.timestamp + roundTrip / 2
                    _serverTimeOffsetMs.value = serverNow - now
                }
            }
            is GameDeletedMessage -> {
                // A dedicated signal, not an error string: the ViewModel used to
                // recognise this by comparing against the exact Spanish sentence,
                // so any rewording (or a translation) silently skipped the local
                // cleanup that follows it.
                _gameDeleted.emit(message.reason)
                disconnect()
            }
            else -> {
                Log.d(TAG, "Unhandled message type: ${message::class.simpleName}")
            }
        }
    }
    
    /**
     * Periodically pings the server so [serverTimeOffsetMs] tracks clock drift.
     *
     * Nothing sent a PING before, which left the PONG handler unreachable and the
     * latency reading permanently at zero.
     */
    private fun startClockSync(session: WebSocketSession) {
        scope?.launch {
            while (isActive) {
                try {
                    pingSentAtMs = System.currentTimeMillis()
                    session.send(json.encodeToString<WsMessage>(PingMessage()))
                } catch (e: Exception) {
                    // The socket is going away; the message loop handles reconnection.
                    return@launch
                }
                delay(CLOCK_SYNC_INTERVAL_MS)
            }
        }
    }

    /**
     * Apply a server-broadcast event to local game state using the persistent engine.
     * Avoids allocating a new GameEngine instance on every incoming broadcast.
     */
    private fun applyEvent(event: GameEvent) {
        val engine = gameEngine ?: run {
            // Edge case: event arrives before WELCOME (e.g. during reconnect race).
            // Bootstrap from current state and promote as the persistent engine.
            val s = _gameState.value ?: return
            GameEngine().also { it.loadState(s); gameEngine = it }
        }
        // applyRemoteEvent, not processEvent: the server already validated this
        // event, and local re-validation silently dropped legitimate updates.
        engine.applyRemoteEvent(event)
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
        if (isShuttingDown || _connectionState.value == ConnectionState.DISCONNECTED) return

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

        reconnectJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var attempts = 0

            while (attempts < MAX_RECONNECT_ATTEMPTS && isActive) {
                // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s, 30s, ...
                val delayMs = minOf(RECONNECT_DELAY_MS * (1L shl attempts), MAX_RECONNECT_DELAY_MS)
                _reconnectAttempt.value = attempts + 1
                Log.i(TAG, "Reconnect attempt ${attempts + 1}/$MAX_RECONNECT_ATTEMPTS (delay: ${delayMs}ms)")
                delay(delayMs)

                val result = connect(url, code, meta, lastReconnectToken, lastAuthToken)
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
        // Before anything else: closing the session below makes the read loop
        // exit into its `finally`, and that must not be read as a dropped link.
        isShuttingDown = true
        _connectionState.value = ConnectionState.DISCONNECTED

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
        pingSentAtMs = null
        _serverTimeOffsetMs.value = 0L
        _latencyMs.value = 0L

        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Clears the teardown guard so a fresh connection can reconnect normally
     * again. Called at the top of every entry point that opens a session.
     */
    private fun beginSession() {
        isShuttingDown = false
    }
    
    // ============== Auth Methods ==============

    suspend fun register(
        serverUrl: String,
        username: String,
        email: String,
        password: String,
        // Previously hardcoded to avatar 0 here, and the profile could not change it
        // afterwards, so every account displayed the same portrait forever.
        avatarId: Int = 0,
        gender: Gender = Gender.M
    ): Result<AuthSuccessMessage> {
        // Auto-generate dummy email if empty (Backward compatibility with older servers)
        val finalEmail = if (email.isBlank()) {
            val sanitized = username.lowercase().replace(Regex("[^a-z0-9]"), "")
            "$sanitized@munchkin.local"
        } else {
            email
        }
        val msg = RegisterMessage(username, finalEmail, password, avatarId, gender)
        return performAuth(serverUrl, msg)
    }

    suspend fun login(
        serverUrl: String,
        email: String,
        password: String
    ): Result<AuthSuccessMessage> {
        val msg = LoginMessage(email, password)
        return performAuth(serverUrl, msg)
    }

    suspend fun loginWithToken(
        serverUrl: String,
        token: String
    ): Result<AuthSuccessMessage> {
        val msg = LoginWithTokenMessage(token)
        return performAuth(serverUrl, msg)
    }

    private suspend fun performAuth(
        serverUrl: String,
        message: WsMessage
    ): Result<AuthSuccessMessage> = withContext(Dispatchers.IO) {
        try {
            val wsUrl = buildWsUrl(serverUrl)
                ?: return@withContext Result.failure(Exception("URL inválida"))

            DLog.i(TAG, "Auth: Connecting...")

            var result: Result<AuthSuccessMessage>? = null

            // use {} so the engine is released even if the handshake throws.
            HttpClient(CIO) { install(WebSockets) }.use { authClient ->
            authClient.webSocket(urlString = wsUrl) {
                // Send auth message - Encoded as WsMessage to preserve "type" field
                val finalJson = json.encodeToString<WsMessage>(message)
                // Never log this payload: RegisterMessage/LoginMessage carry the
                // user's plaintext password, and the reply carries their JWT. The
                // debug log is readable in-app via DebugLogViewer.
                DLog.i(TAG, "Sending auth request: ${message::class.simpleName}")
                send(finalJson)

                // Wait for response
                try {
                    val frame = receiveWithTimeout()
                    if (frame is Frame.Text) {
                        val text = frame.readText()

                        val response = json.decodeFromString<WsMessage>(text)
                        if (response is AuthSuccessMessage) {
                            result = Result.success(response)
                        } else if (response is ErrorMessage) {
                            result = Result.failure(ServerErrorException(response.code, response.message))
                        }
                    }
                } catch (e: Exception) {
                    DLog.e(TAG, "Auth receive error: ${e.message}")
                    result = Result.failure(e)
                }
                close()
            }
            }

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
        val response = sendOneOffRequest(serverUrl, msg)
        
        response.map {
            if (it is CatalogSearchResult) it.results else emptyList()
        }
    }

    suspend fun addMonsterToCatalog(
        serverUrl: String,
        monster: CatalogMonster,
        authToken: String
    ): Result<CatalogMonster> = withContext(Dispatchers.IO) {
        val msg = CatalogAddRequest(monster)
        val response = authenticatedRequest(serverUrl, authToken, msg)

        response.map {
            if (it is CatalogAddSuccess) it.monster else monster
        }
    }

    // ============== History Methods ==============

    /**
     * A player's own game history plus their lifetime totals.
     *
     * This must be an authenticated request: the server serves history only for the
     * session's own account (an unauthenticated one-off request is answered with
     * UNAUTHORIZED), because trusting a client-supplied userId is what let any
     * client read anyone's history.
     */
    suspend fun getHistory(
        serverUrl: String,
        userId: String,
        authToken: String
    ): Result<HistoryResult> = withContext(Dispatchers.IO) {
        authenticatedRequest(serverUrl, authToken, GetHistoryRequest(userId))
            .mapCatching { response ->
                response as? HistoryResult
                    ?: throw Exception("Respuesta inesperada del servidor")
            }
    }
    
    // Generic Helper for One-Off Requests (Catalog, History, etc.)
    private suspend fun sendOneOffRequest(
        serverUrl: String,
        message: WsMessage
    ): Result<WsMessage> = withContext(Dispatchers.IO) {
        try {
            val wsUrl = buildWsUrl(serverUrl)
                ?: return@withContext Result.failure(Exception("URL inválida"))

            var result: Result<WsMessage>? = null

            // use {} so the engine is released even if the handshake throws.
            HttpClient(CIO) { install(WebSockets) }.use { client ->
                client.webSocket(urlString = wsUrl) {
                    val jsonStr = json.encodeToString<WsMessage>(message)
                    send(jsonStr)

                    try {
                        val frame = receiveWithTimeout()
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val response = json.decodeFromString<WsMessage>(text)

                            if (response is ErrorMessage) {
                                result = Result.failure(ServerErrorException(response.code, response.message))
                            } else {
                                result = Result.success(response)
                            }
                        }
                    } catch (e: Exception) {
                        result = Result.failure(e)
                    }
                    close()
                }
            }

            result ?: Result.failure(Exception("Sin respuesta del servidor"))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============== History Methods ==============

    suspend fun sendGameOver(gameId: String, winnerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val currentSession = session ?: return@withContext Result.failure(Exception("No conectado"))
        return@withContext try {
            currentSession.send(json.encodeToString<WsMessage>(GameOverMessage(gameId, winnerId)))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendSwapPlayers(player1: PlayerId, player2: PlayerId): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val msg = SwapPlayers(player1, player2)
            session?.send(json.encodeToString<WsMessage>(msg))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    /**
     * Fetches the ranking. Anyone may read it; passing [authToken] additionally
     * returns the caller's own totals, which the server only knows for a signed-in
     * session.
     */
    suspend fun getLeaderboard(
        serverUrl: String,
        authToken: String? = null
    ): Result<LeaderboardResult> = withContext(Dispatchers.IO) {
        val response = if (authToken.isNullOrBlank()) {
            sendOneOffRequest(serverUrl, GetLeaderboardRequest)
        } else {
            authenticatedRequest(serverUrl, authToken, GetLeaderboardRequest)
        }

        response.mapCatching { message ->
            message as? LeaderboardResult
                ?: throw Exception("Respuesta inesperada del servidor")
        }
    }
    
    suspend fun updateProfile(
        serverUrl: String,
        userId: String,
        username: String?,
        password: String?,
        token: String,
        avatarId: Int? = null,
        gender: Gender? = null
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        val req = UpdateProfileRequest(userId, username, password, avatarId, gender)
        authenticatedRequest(serverUrl, token, req).map { response ->
            if (response is ProfileUpdatedMessage) {
                response.user
            } else {
                throw Exception("Respuesta inesperada del servidor")
            }
        }
    }

    // ============== Hosted Games Methods ==============

    suspend fun getHostedGames(
        serverUrl: String,
        token: String
    ): Result<List<HostedGame>> = withContext(Dispatchers.IO) {
        authenticatedRequest(serverUrl, token, GetHostedGamesRequest).map { response ->
            if (response is HostedGamesResult) {
                response.games
            } else {
                emptyList()
            }
        }
    }

    suspend fun deleteHostedGame(
        serverUrl: String,
        token: String,
        gameId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        authenticatedRequest(serverUrl, token, DeleteHostedGame(gameId)).map { }
    }

    /**
     * Helper for authenticated one-off requests.
     * Connects -> Logs in -> Sends Request -> Waits for Response -> Disconnects.
     */
    private suspend fun authenticatedRequest(
        serverUrl: String,
        token: String,
        request: WsMessage
    ): Result<WsMessage> = withContext(Dispatchers.IO) {
        try {
            val wsUrl = buildWsUrl(serverUrl)
                ?: return@withContext Result.failure(Exception("URL inválida"))

            var result: Result<WsMessage>? = null

            // use {} so the engine is released even if the handshake throws.
            HttpClient(CIO) { install(WebSockets) }.use { client ->
                client.webSocket(urlString = wsUrl) {
                    // 1. Login
                    val loginMsg = LoginWithTokenMessage(token)
                    send(json.encodeToString<WsMessage>(loginMsg))

                    // Wait for Auth Success
                    var authenticated = false
                    try {
                        val frame = receiveWithTimeout()
                        if (frame is Frame.Text) {
                            val response = json.decodeFromString<WsMessage>(frame.readText())
                            if (response is AuthSuccessMessage) {
                                authenticated = true
                            } else if (response is ErrorMessage) {
                                result = Result.failure(ServerErrorException(response.code, response.message))
                            }
                        }
                    } catch (e: Exception) {
                        result = Result.failure(Exception("Auth handshake failed"))
                    }

                    if (authenticated) {
                        // 2. Send Actual Request
                        send(json.encodeToString<WsMessage>(request))

                        // 3. Wait for Response
                        try {
                            val frame = receiveWithTimeout()
                            if (frame is Frame.Text) {
                                val response = json.decodeFromString<WsMessage>(frame.readText())
                                if (response is ErrorMessage) {
                                    // Also fail the call. Previously this only emitted to
                                    // the shared error flow and left result null, so the
                                    // caller surfaced a misleading "No response or auth
                                    // failed" instead of the server's actual reason.
                                    Log.e(TAG, "Server rejected request: ${response.message}")
                                    _errors.emit(response.message)
                                    result = Result.failure(ServerErrorException(response.code, response.message))
                                } else {
                                    result = Result.success(response)
                                }
                            }
                        } catch (e: Exception) {
                            result = Result.failure(Exception("Request failed: ${e.message}"))
                        }
                    }

                    close()
                }
            }

            result ?: Result.failure(Exception("No response or auth failed"))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Builds the normalised ws(s) URL to connect to, preserving the path from the
     * configured server URL. The one-off request helpers used to hardcode "/",
     * so they silently ignored any configured path while connect() honoured it.
     */
    private fun buildWsUrl(serverUrl: String): String? {
        val (host, port, path) = parseWsUrl(serverUrl) ?: return null
        val scheme = if (serverUrl.trim().startsWith("wss://")) "wss" else "ws"
        return "$scheme://$host:$port$path"
    }

    private fun parseWsUrl(url: String): Triple<String, Int, String>? {
        // The port is optional: it previously was not, so a standard-port
        // deployment such as wss://api.example.com/ was rejected as invalid.
        val regex = Regex("""^(wss?)://([^:/?#]+)(?::(\d+))?(/[^?#]*)?""")
        val match = regex.find(url.trim()) ?: return null

        val scheme = match.groupValues[1]
        val isSecure = scheme == "wss"
        if (!isSecure) {
            DLog.w(TAG, "⚠️ Connecting over unencrypted ws://. Use wss:// for production servers.")
        }

        val host = match.groupValues[2]
        if (host.isEmpty()) return null

        val portGroup = match.groupValues[3]
        val port = if (portGroup.isEmpty()) {
            if (isSecure) 443 else 80
        } else {
            portGroup.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }

        val path = match.groupValues[4].ifEmpty { "/" }

        return Triple(host, port, path)
    }
}
