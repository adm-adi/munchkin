package com.munchkin.app.network

import android.util.Log
import com.munchkin.app.BuildConfig
import com.munchkin.app.core.*
import com.munchkin.app.ui.components.DebugLogManager as DLog
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.UUID
import kotlin.random.Random

/**
 * Custom exception representing a backend error code.
 */
class ServerErrorException(val code: ErrorCode, message: String) : Exception(message)

/**
 * WebSocket client for joining and interacting with a remote Munchkin game room,
 * plus one-off account, catalog, discovery, and history RPC helpers.
 */
class GameClient : Closeable {
    companion object {
        private const val TAG = "GameClient"
        private const val RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 15

        private const val RESPONSE_TIMEOUT_MS = 15_000L
        private const val CLOCK_SYNC_INTERVAL_MS = 20_000L
        private const val PING_TIMEOUT_MS = 5_000L

        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val SOCKET_TIMEOUT_MS = 15_000L

        private val WS_URL_REGEX = Regex("""^(wss?)://([^:/?#]+)(?::(\d+))?(/[^?#]*)?$""", RegexOption.IGNORE_CASE)

        private val TERMINAL_ERROR_CODES = setOf(
            ErrorCode.GAME_NOT_FOUND,
            ErrorCode.GAME_FINISHED,
            ErrorCode.INVALID_JOIN_CODE,
            ErrorCode.UNAUTHORIZED,
            ErrorCode.FORBIDDEN
        )

        /**
         * Parses a `ws://` or `wss://` server URL into `(host, port, path)`.
         * Rejects plaintext `ws://` URLs unless [allowInsecure] is true (debug builds).
         */
        internal fun parseWsUrl(
            url: String,
            allowInsecure: Boolean = BuildConfig.DEBUG
        ): Triple<String, Int, String>? {
            val match = WS_URL_REGEX.find(url.trim()) ?: return null
            val scheme = match.groupValues[1].lowercase()
            val isSecure = scheme == "wss"
            if (!isSecure && !allowInsecure) {
                return null
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

    /** Thrown when the server accepts the connection but does not reply in time. */
    class ServerTimeoutException : Exception("El servidor no respondió a tiempo")

    private suspend fun WebSocketSession.receiveWithTimeout(): Frame =
        withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { incoming.receive() }
            ?: throw ServerTimeoutException()

    private val httpClientLock = Any()
    @Volatile
    private var sharedHttpClient: HttpClient? = null
    @Volatile
    private var isClosed = false

    private val lifecycleMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var session: WebSocketSession? = null
    private var clientScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeSessionJob: Job? = null
    private var clockSyncJob: Job? = null
    private var reconnectJob: Job? = null

    private val nudgeChannel = Channel<Unit>(Channel.CONFLATED)
    private val pingTriggerChannel = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var lastUrl: String? = null
    @Volatile
    private var lastJoinCode: String? = null
    @Volatile
    private var lastPlayerMeta: PlayerMeta? = null
    @Volatile
    private var lastReconnectToken: String? = null
    @Volatile
    private var lastAuthToken: String? = null

    private var lastSeq: Long = 0L

    // Persistent engine — reused across events to avoid allocating a new instance per message
    private var gameEngine: GameEngine? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _gameState = MutableStateFlow<GameState?>(null)
    val gameState: StateFlow<GameState?> = _gameState.asStateFlow()

    private val _myPlayerId = MutableStateFlow<PlayerId?>(null)
    val myPlayerId: StateFlow<PlayerId?> = _myPlayerId.asStateFlow()

    private val _errors = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _reconnectToken = MutableStateFlow<String?>(null)
    val reconnectToken: StateFlow<String?> = _reconnectToken.asStateFlow()

    private val _lastCloseReason = MutableStateFlow<CloseReason?>(null)
    val lastCloseReason: StateFlow<CloseReason?> = _lastCloseReason.asStateFlow()

    @Volatile
    private var isShuttingDown = false

    private val _gameDeleted = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val gameDeleted: SharedFlow<String> = _gameDeleted.asSharedFlow()

    private val _serverTimeOffsetMs = MutableStateFlow(0L)
    val serverTimeOffsetMs: StateFlow<Long> = _serverTimeOffsetMs.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    @Volatile
    private var pingSentAtMs: Long? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        coerceInputValues = true
    }

    val currentPlayerId: PlayerId?
        get() = _myPlayerId.value

    val currentReconnectToken: String?
        get() = _reconnectToken.value

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    private fun getOrCreateHttpClient(): HttpClient {
        sharedHttpClient?.let { if (!isClosed) return it }
        return synchronized(httpClientLock) {
            check(!isClosed) { "GameClient has been closed" }
            sharedHttpClient ?: HttpClient(CIO) {
                install(WebSockets) {
                    pingInterval = 15_000
                }
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
            }.also { sharedHttpClient = it }
        }
    }

    private fun ensureScope(): CoroutineScope {
        if (!clientScope.isActive) {
            clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        return clientScope
    }

    private suspend fun resetActiveSessionLocked() {
        reconnectJob?.cancel()
        reconnectJob = null
        clockSyncJob?.cancel()
        clockSyncJob = null
        activeSessionJob?.cancel()
        activeSessionJob = null
        try {
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Replacing session"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        session = null
    }

    /**
     * Create a new game on the remote server.
     */
    suspend fun createGame(
        serverUrl: String,
        playerMeta: PlayerMeta,
        superMunchkin: Boolean = false,
        turnTimerSeconds: Int = 0,
        authToken: String? = null,
        expansionsEnabled: Boolean = false
    ): Result<GameState> = withContext(Dispatchers.IO) {
        try {
            reconnectJob?.cancel()
            reconnectJob = null

            lifecycleMutex.withLock {
                if (isClosed) {
                    return@withContext Result.failure(IllegalStateException("GameClient has been closed"))
                }

                DLog.i(TAG, "Creating game on $serverUrl")
                beginSession()
                resetActiveSessionLocked()
                _connectionState.value = ConnectionState.CONNECTING

                lastUrl = serverUrl
                lastPlayerMeta = playerMeta
                lastAuthToken = authToken

                val wsUrl = buildWsUrl(serverUrl)
                if (wsUrl == null) {
                    DLog.e(TAG, "Invalid URL: $serverUrl")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    return@withContext Result.failure(Exception("URL inválida: $serverUrl"))
                }

                val httpClient = getOrCreateHttpClient()
                val scope = ensureScope()
                val resultDeferred = CompletableDeferred<Result<GameState>>()

                activeSessionJob = scope.launch {
                    try {
                        httpClient.webSocket(urlString = wsUrl) {
                            session = this
                            DLog.i(TAG, "Connected, sending CreateGame...")

                            val authResult = authenticateIfNeeded(authToken)
                            if (authResult.isFailure) {
                                _connectionState.value = ConnectionState.DISCONNECTED
                                resultDeferred.complete(
                                    Result.failure(authResult.exceptionOrNull() ?: Exception("Auth failed"))
                                )
                                return@webSocket
                            }

                            val createRequest = CreateGameRequest(
                                playerMeta = playerMeta,
                                superMunchkin = superMunchkin,
                                turnTimerSeconds = turnTimerSeconds,
                                expansionsEnabled = expansionsEnabled
                            )
                            send(json.encodeToString<WsMessage>(createRequest))
                            DLog.i(TAG, "Waiting for welcome...")

                            val welcomeResult = waitForWelcome()
                            if (welcomeResult.isFailure) {
                                DLog.e(TAG, "Welcome failed: ${welcomeResult.exceptionOrNull()?.message}")
                                _connectionState.value = ConnectionState.DISCONNECTED
                                resultDeferred.complete(
                                    Result.failure(welcomeResult.exceptionOrNull() ?: Exception("Welcome failed"))
                                )
                                return@webSocket
                            }

                            val createdState = synchronized(stateLock) { _gameState.value }
                            if (createdState == null) {
                                _connectionState.value = ConnectionState.DISCONNECTED
                                resultDeferred.complete(Result.failure(Exception("No game state")))
                                return@webSocket
                            }

                            DLog.i(TAG, "Welcome received!")
                            lastJoinCode = createdState.joinCode
                            _connectionState.value = ConnectionState.CONNECTED
                            resultDeferred.complete(Result.success(createdState))

                            startClockSync(this)
                            handleIncomingMessages()
                        }
                    } catch (e: CancellationException) {
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(Result.failure(e))
                        }
                        throw e
                    } catch (e: Exception) {
                        DLog.e(TAG, "Connection error: ${e.message}")
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(Result.failure(e))
                            if (!isShuttingDown) {
                                _connectionState.value = ConnectionState.DISCONNECTED
                            }
                        }
                    } finally {
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(Result.failure(Exception("Conexión cerrada antes de recibir respuesta")))
                        }
                    }
                }

                resultDeferred.await()
            }
        } catch (e: CancellationException) {
            throw e
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
        reconnectJob?.cancel()
        reconnectJob = null
        lifecycleMutex.withLock {
            connectInternal(
                wsUrl = wsUrl,
                joinCode = joinCode,
                playerMeta = playerMeta,
                reconnectToken = reconnectToken,
                authToken = authToken,
                isReconnect = false
            )
        }
    }

    private suspend fun connectInternal(
        wsUrl: String,
        joinCode: String,
        playerMeta: PlayerMeta,
        reconnectToken: String? = null,
        authToken: String? = null,
        isReconnect: Boolean
    ): Result<GameState> {
        if (isClosed) {
            return Result.failure(IllegalStateException("GameClient has been closed"))
        }

        return try {
            DLog.i(TAG, "Connecting to $wsUrl with code $joinCode (reconnect=$isReconnect)")
            if (!isReconnect) {
                beginSession()
                _connectionState.value = ConnectionState.CONNECTING
            } else {
                _connectionState.value = ConnectionState.RECONNECTING
            }

            // Close any previous dangling socket/clock job without cancelling an outer reconnect loop
            clockSyncJob?.cancel()
            clockSyncJob = null
            activeSessionJob?.cancel()
            activeSessionJob = null
            try {
                session?.close(CloseReason(CloseReason.Codes.NORMAL, "Replacing session"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            session = null

            lastUrl = wsUrl
            lastJoinCode = joinCode
            lastPlayerMeta = playerMeta
            lastReconnectToken = reconnectToken
            lastAuthToken = authToken

            val normalizedUrl = buildWsUrl(wsUrl)
            if (normalizedUrl == null) {
                DLog.e(TAG, "Invalid URL format: $wsUrl")
                if (!isReconnect) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                return Result.failure(Exception("URL inválida: $wsUrl"))
            }

            val httpClient = getOrCreateHttpClient()
            val scope = ensureScope()
            val resultDeferred = CompletableDeferred<Result<GameState>>()

            activeSessionJob = scope.launch {
                try {
                    DLog.i(TAG, "Opening WebSocket...")
                    httpClient.webSocket(urlString = normalizedUrl) {
                        session = this
                        DLog.i(TAG, "WS connected, sending hello")

                        val authResult = authenticateIfNeeded(authToken)
                        if (authResult.isFailure) {
                            if (!isReconnect) {
                                _connectionState.value = ConnectionState.DISCONNECTED
                            }
                            resultDeferred.complete(
                                Result.failure(authResult.exceptionOrNull() ?: Exception("Auth failed"))
                            )
                            return@webSocket
                        }

                        val hello = HelloMessage(
                            gameId = "",
                            joinCode = joinCode,
                            playerMeta = playerMeta,
                            reconnectToken = reconnectToken
                        )
                        send(json.encodeToString<WsMessage>(hello))

                        val welcomeResult = waitForWelcome()
                        if (welcomeResult.isFailure) {
                            Log.e(TAG, "Welcome failed: ${welcomeResult.exceptionOrNull()?.message}")
                            if (!isReconnect) {
                                _connectionState.value = ConnectionState.DISCONNECTED
                            }
                            resultDeferred.complete(
                                Result.failure(welcomeResult.exceptionOrNull() ?: Exception("Welcome failed"))
                            )
                            return@webSocket
                        }

                        val state = synchronized(stateLock) { _gameState.value }
                        if (state == null) {
                            if (!isReconnect) {
                                _connectionState.value = ConnectionState.DISCONNECTED
                            }
                            resultDeferred.complete(Result.failure(Exception("No game state")))
                            return@webSocket
                        }

                        _connectionState.value = ConnectionState.CONNECTED
                        resultDeferred.complete(Result.success(state))

                        startClockSync(this)
                        handleIncomingMessages()
                    }
                } catch (e: CancellationException) {
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(Result.failure(e))
                    }
                    throw e
                } catch (e: Exception) {
                    DLog.e(TAG, "WS error: ${e.message}")
                    Log.e(TAG, "WebSocket exception: ${e.message}", e)
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(Result.failure(e))
                        if (!isReconnect && !isShuttingDown) {
                            _connectionState.value = ConnectionState.DISCONNECTED
                        }
                    }
                } finally {
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(Result.failure(Exception("Conexión cerrada antes de recibir respuesta")))
                    }
                }
            }

            resultDeferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed with exception", e)
            if (!isReconnect && !isShuttingDown) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
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
                is ErrorMessage -> {
                    _errors.emit(message.message)
                    Result.failure(ServerErrorException(message.code, message.message))
                }
                else -> Result.failure(Exception("Respuesta de autenticacion inesperada"))
            }
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(Exception("Error al parsear respuesta", e))
        }

        return when (message) {
            is WelcomeMessage -> {
                synchronized(stateLock) {
                    _gameState.value = message.gameState
                    _myPlayerId.value = message.yourPlayerId
                    lastReconnectToken = message.reconnectToken
                    _reconnectToken.value = message.reconnectToken
                    lastSeq = message.gameState.seq
                    val engine = GameEngine()
                    engine.loadState(message.gameState)
                    gameEngine = engine
                }
                Result.success(Unit)
            }
            is StateSnapshotMessage -> {
                synchronized(stateLock) {
                    _gameState.value = message.gameState
                    lastSeq = message.seq
                    val engine = gameEngine ?: GameEngine().also { gameEngine = it }
                    engine.loadState(message.gameState)
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
                        DLog.i(TAG, "Received message (${text.length} chars)")
                        val message = try {
                            json.decodeFromString<WsMessage>(text)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            DLog.e(TAG, "Failed to parse: ${e.message}")
                            Log.e(TAG, "Failed to parse message", e)
                            continue
                        }

                        DLog.i(TAG, "Parsed as: ${message::class.simpleName}")
                        try {
                            handleMessage(message)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            DLog.e(TAG, "Error handling message: ${e.message}")
                            Log.e(TAG, "Error handling message", e)
                        }
                    }
                    is Frame.Close -> {
                        val reason = frame.readReason()
                        _lastCloseReason.value = reason
                        Log.i(TAG, "Server closed connection: code=${reason?.code}, message=${reason?.message}")
                        break
                    }
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Message loop error", e)
        } finally {
            if (session === this) {
                session = null
            }
            clockSyncJob?.cancel()
            clockSyncJob = null
            handleDisconnect()
        }
    }

    /**
     * Handle a received message.
     */
    private suspend fun handleMessage(message: WsMessage) {
        when (message) {
            is StateSnapshotMessage -> {
                synchronized(stateLock) {
                    _gameState.value = message.gameState
                    lastSeq = message.seq
                    val engine = gameEngine ?: GameEngine().also { gameEngine = it }
                    engine.loadState(message.gameState)
                }
            }
            is EventBroadcastMessage -> {
                applyEvent(message.event, message.seq)
            }
            is PlayerStatusMessage -> {
                updatePlayerStatus(message.playerId, message.isConnected)
            }
            is ErrorMessage -> {
                _errors.emit(message.message)
            }
            is PongMessage -> {
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
                _gameDeleted.emit(message.reason)
                disconnect()
            }
            else -> {
                Log.d(TAG, "Unhandled message type: ${message::class.simpleName}")
            }
        }
    }

    /**
     * Periodically pings the server so [serverTimeOffsetMs] tracks clock drift and
     * enforces a 5s PONG timeout to detect silently dropped links promptly.
     */
    private fun startClockSync(targetSession: WebSocketSession) {
        clockSyncJob?.cancel()
        clockSyncJob = ensureScope().launch {
            while (isActive && session === targetSession && !isShuttingDown) {
                try {
                    val sentAt = System.currentTimeMillis()
                    pingSentAtMs = sentAt
                    targetSession.send(json.encodeToString<WsMessage>(PingMessage()))
                    delay(PING_TIMEOUT_MS)

                    val pending = pingSentAtMs
                    if (pending != null && pending == sentAt && session === targetSession && !isShuttingDown) {
                        DLog.w(TAG, "PONG timeout after ${PING_TIMEOUT_MS}ms; closing session")
                        try {
                            targetSession.close(
                                CloseReason(CloseReason.Codes.GOING_AWAY, "Ping timeout")
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch
                }

                val remainingDelay = (CLOCK_SYNC_INTERVAL_MS - PING_TIMEOUT_MS).coerceAtLeast(1_000L)
                withTimeoutOrNull(remainingDelay) {
                    pingTriggerChannel.receive()
                }
            }
        }
    }

    /**
     * Triggers an immediate reconnect attempt (when reconnecting or permanently failed)
     * or an immediate ping/PONG check (when connected).
     */
    fun nudge() {
        when (_connectionState.value) {
            ConnectionState.CONNECTED -> {
                pingTriggerChannel.trySend(Unit)
            }
            ConnectionState.RECONNECTING -> {
                if (reconnectJob?.isActive == true) {
                    nudgeChannel.trySend(Unit)
                } else {
                    attemptReconnect()
                }
            }
            ConnectionState.FAILED_PERMANENTLY -> {
                if (!isShuttingDown && !isClosed && lastUrl != null && lastJoinCode != null && lastPlayerMeta != null) {
                    beginSession()
                    _connectionState.value = ConnectionState.RECONNECTING
                    attemptReconnect()
                    nudgeChannel.trySend(Unit)
                }
            }
            ConnectionState.CONNECTING,
            ConnectionState.DISCONNECTED -> Unit
        }
    }

    /**
     * Apply a server-broadcast event to local game state using the persistent engine.
     */
    private fun applyEvent(event: GameEvent, seq: Long) {
        synchronized(stateLock) {
            if (lastSeq > 0L && seq <= lastSeq) {
                DLog.w(TAG, "Ignoring stale or duplicate event seq=$seq (lastSeq=$lastSeq)")
                return
            }
            var seqGapDetected = false
            if (lastSeq > 0L && seq > lastSeq + 1L) {
                DLog.w(TAG, "Sequence mismatch: expected=${lastSeq + 1}, received=$seq; requesting resync")
                Log.w(TAG, "Sequence mismatch: expected=${lastSeq + 1}, received=$seq; requesting resync")
                seqGapDetected = true
            }
            lastSeq = seq

            val engine = gameEngine ?: run {
                val s = _gameState.value ?: return
                GameEngine().also { it.loadState(s); gameEngine = it }
            }
            engine.applyRemoteEvent(event)
            _gameState.value = engine.gameState.value
            if (seqGapDetected) {
                requestSnapshotResync()
            }
        }
    }

    private fun requestSnapshotResync() {
        val currentSession = session ?: return
        val code = lastJoinCode ?: return
        val meta = lastPlayerMeta ?: return
        val token = lastReconnectToken ?: _reconnectToken.value
        ensureScope().launch {
            try {
                val hello = HelloMessage(
                    gameId = _gameState.value?.gameId?.value ?: "",
                    joinCode = code,
                    playerMeta = meta,
                    reconnectToken = token
                )
                currentSession.send(json.encodeToString<WsMessage>(hello))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot resync HELLO failed: ${e.message}")
            }
        }
    }

    /**
     * Update player connection status in local state under [stateLock].
     */
    private fun updatePlayerStatus(playerId: PlayerId, isConnected: Boolean) {
        synchronized(stateLock) {
            val engine = gameEngine
            if (engine != null) {
                engine.updatePlayerStatus(playerId, isConnected)
                _gameState.value = engine.gameState.value
            } else {
                val currentState = _gameState.value ?: return
                val player = currentState.players[playerId] ?: return
                val updatedPlayer = player.copy(isConnected = isConnected)
                _gameState.value = currentState.copy(
                    players = currentState.players + (playerId to updatedPlayer)
                )
            }
        }
    }

    /**
     * Handle disconnection.
     */
    private fun handleDisconnect() {
        if (isShuttingDown || isClosed || _connectionState.value == ConnectionState.DISCONNECTED) return

        _connectionState.value = ConnectionState.RECONNECTING
        attemptReconnect()
    }

    /**
     * Attempt to reconnect with exponential backoff + jitter (0..25%).
     * Stops immediately on terminal server error codes and sets [ConnectionState.FAILED_PERMANENTLY].
     */
    private fun attemptReconnect() {
        val url = lastUrl ?: run {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val code = lastJoinCode ?: run {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val meta = lastPlayerMeta ?: run {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        if (reconnectJob?.isActive == true) return
        while (nudgeChannel.tryReceive().isSuccess) {
            // Drain stale signals before entering backoff loop.
        }

        reconnectJob = ensureScope().launch {
            var attempts = 0

            while (attempts < MAX_RECONNECT_ATTEMPTS && isActive && !isShuttingDown && !isClosed) {
                val baseDelay = minOf(
                    RECONNECT_DELAY_MS * (1L shl attempts.coerceAtMost(10)),
                    MAX_RECONNECT_DELAY_MS
                )
                val jitter = (baseDelay * Random.nextDouble(0.0, 0.25)).toLong()
                val delayMs = baseDelay + jitter

                _reconnectAttempt.value = attempts + 1
                _connectionState.value = ConnectionState.RECONNECTING
                Log.i(TAG, "Reconnect attempt ${attempts + 1}/$MAX_RECONNECT_ATTEMPTS (delay: ${delayMs}ms)")

                withTimeoutOrNull(delayMs) {
                    nudgeChannel.receive()
                }
                if (!isActive || isShuttingDown || isClosed) return@launch

                val result = lifecycleMutex.withLock {
                    if (!isActive || isShuttingDown || isClosed) return@launch
                    connectInternal(
                        wsUrl = url,
                        joinCode = code,
                        playerMeta = meta,
                        reconnectToken = lastReconnectToken,
                        authToken = lastAuthToken,
                        isReconnect = true
                    )
                }

                if (result.isSuccess) {
                    Log.i(TAG, "Reconnected successfully on attempt ${attempts + 1}")
                    _reconnectAttempt.value = 0
                    return@launch
                }

                val error = result.exceptionOrNull()
                if (error is ServerErrorException && error.code in TERMINAL_ERROR_CODES) {
                    Log.w(TAG, "Terminal reconnect error (${error.code}): ${error.message}")
                    _reconnectAttempt.value = 0
                    _connectionState.value = ConnectionState.FAILED_PERMANENTLY
                    return@launch
                }

                attempts++
            }

            if (!isShuttingDown && !isClosed) {
                Log.e(TAG, "Failed to reconnect after $MAX_RECONNECT_ATTEMPTS attempts")
                _reconnectAttempt.value = 0
                _connectionState.value = ConnectionState.FAILED_PERMANENTLY
            }
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendTransferHost(targetPlayerId: PlayerId): Result<Unit> {
        val currentSession = session ?: return Result.failure(Exception("No conectado"))
        return try {
            val msg = TransferHostMessage(targetPlayerId)
            val jsonStr = json.encodeToString<WsMessage>(msg)
            currentSession.send(jsonStr)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendUndoLast(): Result<Unit> {
        val currentSession = session ?: return Result.failure(Exception("No conectado"))

        return try {
            val jsonStr = json.encodeToString<WsMessage>(UndoLastMessage)
            currentSession.send(jsonStr)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendPauseTurnTimer(): Result<Unit> {
        val currentSession = session ?: return Result.failure(Exception("No conectado"))
        return try {
            currentSession.send(json.encodeToString<WsMessage>(PauseTurnTimerMessage))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendResumeTurnTimer(): Result<Unit> {
        val currentSession = session ?: return Result.failure(Exception("No conectado"))
        return try {
            currentSession.send(json.encodeToString<WsMessage>(ResumeTurnTimerMessage))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Connect to a room in read-only spectator/TV mode without taking a player seat.
     */
    suspend fun watchGame(
        wsUrl: String,
        joinCode: String
    ): Result<GameState> = withContext(Dispatchers.IO) {
        reconnectJob?.cancel()
        reconnectJob = null
        lifecycleMutex.withLock {
            if (isClosed) {
                return@withContext Result.failure(IllegalStateException("GameClient has been closed"))
            }
            try {
                beginSession()
                resetActiveSessionLocked()
                _connectionState.value = ConnectionState.CONNECTING
                val normalizedUrl = buildWsUrl(wsUrl)
                    ?: return@withContext Result.failure(Exception("URL inválida: $wsUrl"))
                val httpClient = getOrCreateHttpClient()
                val scope = ensureScope()
                val resultDeferred = CompletableDeferred<Result<GameState>>()
                activeSessionJob = scope.launch {
                    try {
                        httpClient.webSocket(urlString = normalizedUrl) {
                            session = this
                            send(json.encodeToString<WsMessage>(WatchGameMessage(joinCode.trim().uppercase())))
                            val welcomeResult = waitForWelcome()
                            if (welcomeResult.isFailure) {
                                _connectionState.value = ConnectionState.DISCONNECTED
                                resultDeferred.complete(
                                    Result.failure(welcomeResult.exceptionOrNull() ?: Exception("Watch failed"))
                                )
                                return@webSocket
                            }
                            val watchedState = synchronized(stateLock) { _gameState.value }
                            if (watchedState == null) {
                                _connectionState.value = ConnectionState.DISCONNECTED
                                resultDeferred.complete(Result.failure(Exception("No game state")))
                                return@webSocket
                            }
                            _connectionState.value = ConnectionState.CONNECTED
                            resultDeferred.complete(Result.success(watchedState))
                            startClockSync(this)
                            handleIncomingMessages()
                        }
                    } catch (e: CancellationException) {
                        if (!resultDeferred.isCompleted) resultDeferred.cancel(e)
                        throw e
                    } catch (e: Exception) {
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(Result.failure(e))
                            if (!isShuttingDown) _connectionState.value = ConnectionState.DISCONNECTED
                        }
                    } finally {
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(Result.failure(Exception("Conexión cerrada")))
                        }
                    }
                }
                resultDeferred.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.DISCONNECTED
                Result.failure(e)
            }
        }
    }

    /**
     * Disconnect from the server while keeping the client instance reusable.
     * Call [close] when the client is permanently discarded.
     */
    suspend fun disconnect() {
        isShuttingDown = true
        _connectionState.value = ConnectionState.DISCONNECTED

        reconnectJob?.cancel()
        reconnectJob = null
        clockSyncJob?.cancel()
        clockSyncJob = null
        activeSessionJob?.cancel()
        activeSessionJob = null

        withContext(NonCancellable) {
            lifecycleMutex.withLock {
                try {
                    session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnecting"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing session", e)
                }

                session = null
                synchronized(stateLock) {
                    gameEngine = null
                    lastSeq = 0L
                }
                _reconnectAttempt.value = 0
                pingSentAtMs = null
                _serverTimeOffsetMs.value = 0L
                _latencyMs.value = 0L
                _lastCloseReason.value = null

                clientScope.cancel()
                if (!isClosed) {
                    clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                }

                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    override fun close() {
        isShuttingDown = true
        isClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        clockSyncJob?.cancel()
        clockSyncJob = null
        activeSessionJob?.cancel()
        activeSessionJob = null
        session = null
        clientScope.cancel()
        synchronized(httpClientLock) {
            sharedHttpClient?.close()
            sharedHttpClient = null
        }
    }

    private fun beginSession() {
        isShuttingDown = false
    }

    // ============== Auth Methods ==============

    suspend fun register(
        serverUrl: String,
        username: String,
        email: String,
        password: String,
        avatarId: Int = 0,
        gender: Gender = Gender.M
    ): Result<AuthSuccessMessage> {
        val finalEmail = if (email.isBlank()) {
            val sanitized = username.lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { "player" }
            val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
            "$sanitized.$suffix@munchkin.local"
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
            val httpClient = getOrCreateHttpClient()
            var result: Result<AuthSuccessMessage>? = null

            httpClient.webSocket(urlString = wsUrl) {
                val finalJson = json.encodeToString<WsMessage>(message)
                DLog.i(TAG, "Sending auth request: ${message::class.simpleName}")
                send(finalJson)

                try {
                    val frame = receiveWithTimeout()
                    if (frame is Frame.Text) {
                        val response = json.decodeFromString<WsMessage>(frame.readText())
                        if (response is AuthSuccessMessage) {
                            result = Result.success(response)
                        } else if (response is ErrorMessage) {
                            result = Result.failure(ServerErrorException(response.code, response.message))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DLog.e(TAG, "Auth receive error: ${e.message}")
                    result = Result.failure(e)
                }
                close()
            }

            result ?: Result.failure(Exception("No response from server"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DLog.e(TAG, "Auth error: ${e.message}")
            Result.failure(e)
        }
    }

    // ============== Discovery Methods ==============

    suspend fun listGames(serverUrl: String): Result<List<DiscoveredGame>> = withContext(Dispatchers.IO) {
        sendOneOffRequest(serverUrl, ListGamesRequest).mapCatching { response ->
            when (response) {
                is GameListMessage -> response.games
                else -> throw Exception("Respuesta inesperada del servidor")
            }
        }
    }

    // ============== Catalog Methods ==============

    suspend fun searchMonsters(
        serverUrl: String,
        query: String,
        expansion: String? = null,
        limit: Int = 20,
        offset: Int = 0,
        isUndead: Boolean? = null,
        minLevel: Int? = null,
        maxLevel: Int? = null,
        authToken: String? = null
    ): Result<List<CatalogMonster>> = withContext(Dispatchers.IO) {
        val msg = CatalogSearchRequest(
            query = query,
            expansion = expansion,
            limit = limit,
            offset = offset,
            isUndead = isUndead,
            minLevel = minLevel,
            maxLevel = maxLevel
        )
        val response = if (!authToken.isNullOrBlank()) {
            authenticatedRequest(serverUrl, authToken, msg)
        } else {
            sendOneOffRequest(serverUrl, msg)
        }

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

    suspend fun updateMonsterInCatalog(
        serverUrl: String,
        monsterId: String,
        monster: CatalogMonster,
        authToken: String
    ): Result<CatalogMonster> = withContext(Dispatchers.IO) {
        val msg = CatalogUpdateRequest(monsterId = monsterId, monster = monster)
        val response = authenticatedRequest(serverUrl, authToken, msg)

        response.mapCatching {
            (it as? CatalogUpdateSuccess)?.monster
                ?: throw Exception("Respuesta inesperada del servidor")
        }
    }

    suspend fun deleteMonsterFromCatalog(
        serverUrl: String,
        monsterId: String,
        authToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val msg = CatalogDeleteRequest(monsterId = monsterId)
        val response = authenticatedRequest(serverUrl, authToken, msg)

        response.mapCatching {
            (it as? CatalogDeleteSuccess)?.monsterId
                ?: throw Exception("Respuesta inesperada del servidor")
        }
    }

    // ============== History Methods ==============

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

    private suspend fun sendOneOffRequest(
        serverUrl: String,
        message: WsMessage
    ): Result<WsMessage> = withContext(Dispatchers.IO) {
        try {
            val wsUrl = buildWsUrl(serverUrl)
                ?: return@withContext Result.failure(Exception("URL inválida"))

            val httpClient = getOrCreateHttpClient()
            var result: Result<WsMessage>? = null

            httpClient.webSocket(urlString = wsUrl) {
                send(json.encodeToString<WsMessage>(message))

                try {
                    val frame = receiveWithTimeout()
                    if (frame is Frame.Text) {
                        val response = json.decodeFromString<WsMessage>(frame.readText())
                        result = if (response is ErrorMessage) {
                            Result.failure(ServerErrorException(response.code, response.message))
                        } else {
                            Result.success(response)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    result = Result.failure(e)
                }
                close()
            }

            result ?: Result.failure(Exception("Sin respuesta del servidor"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============== Game / Session Admin Methods ==============

    suspend fun sendGameOver(gameId: String, winnerId: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val currentSession = session ?: return@withContext Result.failure(Exception("No conectado"))
        return@withContext try {
            currentSession.send(json.encodeToString<WsMessage>(GameOverMessage(gameId, winnerId)))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendSwapPlayers(player1: PlayerId, player2: PlayerId): Result<Unit> = withContext(Dispatchers.IO) {
        val currentSession = session ?: return@withContext Result.failure(Exception("No conectado"))
        return@withContext try {
            val msg = SwapPlayers(player1, player2)
            currentSession.send(json.encodeToString<WsMessage>(msg))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
        gender: Gender? = null,
        currentPassword: String? = null
    ): Result<ProfileUpdatedMessage> = withContext(Dispatchers.IO) {
        val req = UpdateProfileRequest(
            userId = userId,
            username = username,
            password = password,
            avatarId = avatarId,
            gender = gender,
            currentPassword = currentPassword
        )
        authenticatedRequest(serverUrl, token, req).mapCatching { response ->
            response as? ProfileUpdatedMessage
                ?: throw Exception("Respuesta inesperada del servidor")
        }
    }

    suspend fun deleteAccount(
        serverUrl: String,
        password: String,
        token: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        authenticatedRequest(serverUrl, token, DeleteAccountRequest(password)).mapCatching { response ->
            if (response !is AccountDeletedMessage) {
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

    private suspend fun authenticatedRequest(
        serverUrl: String,
        token: String,
        request: WsMessage
    ): Result<WsMessage> = withContext(Dispatchers.IO) {
        try {
            val wsUrl = buildWsUrl(serverUrl)
                ?: return@withContext Result.failure(Exception("URL inválida"))

            val httpClient = getOrCreateHttpClient()
            var result: Result<WsMessage>? = null

            httpClient.webSocket(urlString = wsUrl) {
                val loginMsg = LoginWithTokenMessage(token)
                send(json.encodeToString<WsMessage>(loginMsg))

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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    result = Result.failure(Exception("Auth handshake failed: ${e.message}", e))
                }

                if (authenticated) {
                    send(json.encodeToString<WsMessage>(request))

                    try {
                        val frame = receiveWithTimeout()
                        if (frame is Frame.Text) {
                            val response = json.decodeFromString<WsMessage>(frame.readText())
                            if (response is ErrorMessage) {
                                Log.e(TAG, "Server rejected request: ${response.message}")
                                _errors.emit(response.message)
                                result = Result.failure(ServerErrorException(response.code, response.message))
                            } else {
                                result = Result.success(response)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        result = Result.failure(Exception("Request failed: ${e.message}", e))
                    }
                }

                close()
            }

            result ?: Result.failure(Exception("No response or auth failed"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun parseWsUrl(
        url: String,
        allowInsecure: Boolean = BuildConfig.DEBUG
    ): Triple<String, Int, String>? = Companion.parseWsUrl(url, allowInsecure)

    private fun buildWsUrl(
        serverUrl: String,
        allowInsecure: Boolean = BuildConfig.DEBUG
    ): String? {
        val (host, port, path) = parseWsUrl(serverUrl, allowInsecure) ?: return null
        val isSecure = serverUrl.trim().startsWith("wss://", ignoreCase = true)
        if (!isSecure) {
            DLog.w(TAG, "Connecting over unencrypted ws://. Use wss:// for production servers.")
        }
        val scheme = if (isSecure) "wss" else "ws"
        return "$scheme://$host:$port$path"
    }
}
