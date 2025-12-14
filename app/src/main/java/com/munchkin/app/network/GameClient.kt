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
    
    /**
     * Connect to a game server.
     */
    suspend fun connect(
        wsUrl: String,
        joinCode: String,
        playerMeta: PlayerMeta
    ): Result<GameState> = withContext(Dispatchers.IO) {
        try {
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
                _connectionState.value = ConnectionState.DISCONNECTED
                return@withContext Result.failure(Exception("URL inválida"))
            }
            
            val (host, port, path) = urlParts
            
            // Connect
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            client!!.webSocket(host = host, port = port, path = path) {
                session = this
                
                // Send hello
                val hello = HelloMessage(
                    gameId = "",  // Will be validated by join code
                    joinCode = joinCode,
                    playerMeta = playerMeta
                )
                send(json.encodeToString<WsMessage>(hello))
                
                // Wait for welcome or error
                val welcomeResult = waitForWelcome()
                
                if (welcomeResult.isFailure) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    return@webSocket
                }
                
                _connectionState.value = ConnectionState.CONNECTED
                
                // Start message loop
                handleIncomingMessages()
            }
            
            val state = _gameState.value
            if (state != null) {
                Result.success(state)
            } else {
                Result.failure(Exception("No se recibió estado del servidor"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
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
                        val message = try {
                            json.decodeFromString<WsMessage>(text)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse message", e)
                            continue
                        }
                        
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
    
    /**
     * Parse WebSocket URL into components.
     */
    private fun parseWsUrl(url: String): Triple<String, Int, String>? {
        val regex = Regex("""ws://([^:]+):(\d+)(/.*)?""")
        val match = regex.find(url) ?: return null
        
        val host = match.groupValues[1]
        val port = match.groupValues[2].toIntOrNull() ?: return null
        val path = match.groupValues.getOrNull(3)?.ifEmpty { "/game" } ?: "/game"
        
        return Triple(host, port, path)
    }
}
