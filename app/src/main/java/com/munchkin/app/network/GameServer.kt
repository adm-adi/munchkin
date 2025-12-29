package com.munchkin.app.network

import android.util.Log
import com.munchkin.app.core.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import com.munchkin.app.ui.components.DebugLogManager as DLog

/**
 * WebSocket server for the game host.
 * Manages client connections and broadcasts events.
 * Uses CIO engine for better Android compatibility.
 */
class GameServer(
    private val gameEngine: GameEngine,
    private val port: Int = 8765
) {
    companion object {
        private const val TAG = "GameServer"
    }
    
    private var server: ApplicationEngine? = null
    private val clients = ConcurrentHashMap<PlayerId, WebSocketSession>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    
    /**
     * Start the WebSocket server using CIO engine (Android-friendly).
     */
    suspend fun start(): Result<String> = withContext(Dispatchers.IO) {
        try {
            _serverState.value = ServerState.STARTING
            DLog.i(TAG, "Starting server on 0.0.0.0:$port")
            Log.i(TAG, "Creating CIO server on 0.0.0.0:$port...")
            
            // IMPORTANT: host = "0.0.0.0" allows connections from OTHER devices on LAN
            // Default localhost only allows local connections
            server = embeddedServer(CIO, host = "0.0.0.0", port = port) {
                install(WebSockets) {
                    pingPeriodMillis = 15000
                    timeoutMillis = 30000
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                
                routing {
                    webSocket("/game") {
                        handleClientConnection(this)
                    }
                }
            }
            
            DLog.i(TAG, "Server created, starting...")
            Log.i(TAG, "Starting server...")
            server?.start(wait = false)
            
            // Give the server a moment to start
            delay(100)
            
            _serverState.value = ServerState.RUNNING
            DLog.i(TAG, "✅ Server RUNNING on port $port")
            Log.i(TAG, "Server started successfully on port $port")
            
            Result.success("ws://localhost:$port/game")
        } catch (e: Exception) {
            DLog.e(TAG, "❌ Server FAILED: ${e.message}")
            Log.e(TAG, "Failed to start server", e)
            _serverState.value = ServerState.ERROR
            Result.failure(e)
        }
    }
    
    /**
     * Stop the WebSocket server.
     * @param graceful If true, broadcast handover to next host candidate before stopping
     */
    suspend fun stop(graceful: Boolean = false, nextHostWsUrl: String? = null) = withContext(Dispatchers.IO) {
        try {
            // Graceful handover - notify clients of new host
            if (graceful && nextHostWsUrl != null) {
                val newHostId = selectNextHost()
                if (newHostId != null) {
                    val handoverMsg = HandoverInitMessage(
                        newHostId = newHostId,
                        newEpoch = (gameEngine.gameState.value?.epoch ?: 0) + 1,
                        wsUrl = nextHostWsUrl
                    )
                    broadcastMessage(handoverMsg)
                    Log.i(TAG, "Broadcasted handover to $newHostId at $nextHostWsUrl")
                    delay(500) // Give clients time to process
                }
            }
            
            clients.values.forEach { session ->
                try {
                    session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server stopping"))
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing client session", e)
                }
            }
            clients.clear()
            
            server?.stop(1000, 2000)
            server = null
            
            _serverState.value = ServerState.STOPPED
            Log.i(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }
    
    /**
     * Select next host candidate based on join order.
     */
    private fun selectNextHost(): PlayerId? {
        val state = gameEngine.gameState.value ?: return null
        return state.players.values
            .filter { it.isConnected && clients.containsKey(it.playerId) }
            .firstOrNull()?.playerId
    }
    
    /**
     * Broadcast a message to all connected clients.
     */
    private suspend fun broadcastMessage(message: WsMessage) {
        val msgText = json.encodeToString<WsMessage>(message)
        clients.values.forEach { session ->
            try {
                session.send(msgText)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send to client", e)
            }
        }
    }
    
    /**
     * Handle a new client WebSocket connection.
     */
    private suspend fun handleClientConnection(session: WebSocketSession) {
        var playerId: PlayerId? = null
        
        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        val message = try {
                            json.decodeFromString<WsMessage>(text)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse message: $text", e)
                            session.sendError(ErrorCode.INTERNAL_ERROR, "Invalid message format")
                            continue
                        }
                        
                        playerId = handleMessage(session, message, playerId)
                    }
                    is Frame.Close -> {
                        Log.i(TAG, "Client closed connection: $playerId")
                        break
                    }
                    else -> { /* Ignore other frame types */ }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client connection error", e)
        } finally {
            playerId?.let { id ->
                clients.remove(id)
                handlePlayerDisconnect(id)
            }
        }
    }
    
    /**
     * Handle an incoming message from a client.
     */
    private suspend fun handleMessage(
        session: WebSocketSession,
        message: WsMessage,
        currentPlayerId: PlayerId?
    ): PlayerId? {
        return when (message) {
            is HelloMessage -> handleHello(session, message)
            is EventRequestMessage -> {
                currentPlayerId?.let { handleEventRequest(session, message, it) }
                currentPlayerId
            }
            is PingMessage -> {
                session.send(json.encodeToString<WsMessage>(PongMessage()))
                currentPlayerId
            }
            else -> currentPlayerId
        }
    }
    
    /**
     * Handle client hello/join request.
     */
    private suspend fun handleHello(session: WebSocketSession, message: HelloMessage): PlayerId? {
        Log.i(TAG, "Received Hello from ${message.playerMeta.name} with code ${message.joinCode}")
        
        val gameState = gameEngine.gameState.value
        
        // Validate join code
        if (gameState == null) {
            Log.e(TAG, "handleHello: gameState is NULL!")
            session.sendError(ErrorCode.INVALID_JOIN_CODE, "Partida no iniciada")
            return null
        }
        
        if (gameState.joinCode != message.joinCode) {
            Log.e(TAG, "handleHello: Code mismatch! Expected ${gameState.joinCode}, got ${message.joinCode}")
            session.sendError(ErrorCode.INVALID_JOIN_CODE, "Código de partida inválido")
            return null
        }
        
        Log.i(TAG, "Join code validated successfully")
        
        // Check if this is a reconnecting player
        val existingPlayer = gameState.players[message.playerMeta.playerId]
        
        if (existingPlayer != null) {
            // Reconnecting player
            clients[message.playerMeta.playerId] = session
            
            // Send current state
            val snapshot = StateSnapshotMessage(gameState, gameState.seq)
            session.send(json.encodeToString<WsMessage>(snapshot))
            
            // Broadcast reconnection
            broadcastPlayerStatus(message.playerMeta.playerId, true)
            
            Log.i(TAG, "Player reconnected: ${message.playerMeta.name}")
            return message.playerMeta.playerId
        }
        
        // New player joining
        val joinEvent = PlayerJoin(
            eventId = java.util.UUID.randomUUID().toString(),
            actorId = message.playerMeta.playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = null,
            playerMeta = message.playerMeta,
            lastKnownIp = message.lastKnownIp
        )
        
        val result = gameEngine.processEvent(joinEvent)
        
        when (result) {
            is ValidationResult.Success -> {
                clients[message.playerMeta.playerId] = session
                
                // Send welcome with state
                val welcome = WelcomeMessage(
                    gameState = result.state,
                    yourPlayerId = message.playerMeta.playerId
                )
                session.send(json.encodeToString<WsMessage>(welcome))
                
                // Broadcast join to other clients
                result.envelope?.let { broadcastEvent(it, exclude = message.playerMeta.playerId) }
                
                Log.i(TAG, "Player joined: ${message.playerMeta.name}")
                return message.playerMeta.playerId
            }
            is ValidationResult.Error -> {
                session.sendError(ErrorCode.VALIDATION_FAILED, result.message)
                return null
            }
        }
    }
    
    /**
     * Handle event request from a client.
     */
    private suspend fun handleEventRequest(
        session: WebSocketSession,
        message: EventRequestMessage,
        playerId: PlayerId
    ) {
        // Ensure actor matches the connected player
        if (message.event.actorId != playerId) {
            session.sendError(ErrorCode.UNAUTHORIZED, "No autorizado")
            return
        }
        
        val result = gameEngine.processEvent(message.event)
        
        when (result) {
            is ValidationResult.Success -> {
                result.envelope?.let { broadcastEvent(it) }
            }
            is ValidationResult.Error -> {
                session.sendError(ErrorCode.VALIDATION_FAILED, result.message)
            }
        }
    }
    
    /**
     * Handle player disconnect.
     */
    private suspend fun handlePlayerDisconnect(playerId: PlayerId) {
        val gameState = gameEngine.gameState.value ?: return
        
        // Update player connection status
        broadcastPlayerStatus(playerId, false)
        
        Log.i(TAG, "Player disconnected: $playerId")
    }
    
    /**
     * Broadcast an event to all connected clients.
     */
    private suspend fun broadcastEvent(envelope: EventEnvelope, exclude: PlayerId? = null) {
        val message = EventBroadcastMessage(
            gameId = envelope.gameId.value,
            epoch = envelope.epoch,
            seq = envelope.seq,
            event = envelope.event
        )
        val json = json.encodeToString<WsMessage>(message)
        
        clients.forEach { (id, session) ->
            if (id != exclude) {
                try {
                    session.send(json)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send to client $id", e)
                }
            }
        }
    }
    
    /**
     * Broadcast player status change.
     */
    private suspend fun broadcastPlayerStatus(playerId: PlayerId, isConnected: Boolean) {
        val message = PlayerStatusMessage(playerId, isConnected)
        val json = json.encodeToString<WsMessage>(message)
        
        clients.forEach { (id, session) ->
            if (id != playerId) {
                try {
                    session.send(json)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send status to client $id", e)
                }
            }
        }
    }
    
    /**
     * Send error message to a session.
     */
    private suspend fun WebSocketSession.sendError(code: ErrorCode, message: String) {
        val error = ErrorMessage(code, message)
        send(json.encodeToString<WsMessage>(error))
    }
    
    /**
     * Get the number of connected clients.
     */
    fun getConnectedCount(): Int = clients.size
}

enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}
