package com.munchkin.app.network

import com.munchkin.app.core.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WebSocket protocol messages for client-server communication.
 */

@Serializable
sealed class WsMessage

// ============== Client → Host Messages ==============

/**
 * Initial handshake when client connects.
 */
@Serializable
@SerialName("HELLO")
data class HelloMessage(
    val gameId: String,
    val joinCode: String,
    val playerMeta: PlayerMeta,
    val lastKnownIp: String? = null
) : WsMessage()

/**
 * Client requests to process an event.
 */
@Serializable
@SerialName("EVENT_REQUEST")
data class EventRequestMessage(
    val event: GameEvent
) : WsMessage()

/**
 * Client ping for keepalive.
 */
@Serializable
@SerialName("PING")
data class PingMessage(
    val timestamp: Long = System.currentTimeMillis()
) : WsMessage()

// ============== Host → Client Messages ==============

/**
 * Welcome response with full game state.
 */
@Serializable
@SerialName("WELCOME")
data class WelcomeMessage(
    val gameState: GameState,
    val yourPlayerId: PlayerId
) : WsMessage()

/**
 * Full state snapshot for sync/reconnection.
 */
@Serializable
@SerialName("STATE_SNAPSHOT")
data class StateSnapshotMessage(
    val gameState: GameState,
    val seq: Long
) : WsMessage()

/**
 * Broadcast of a validated event to all clients.
 */
@Serializable
@SerialName("EVENT_BROADCAST")
data class EventBroadcastMessage(
    val gameId: String,
    val epoch: Int,
    val seq: Long,
    val event: GameEvent
) : WsMessage()

/**
 * Error response when event validation fails.
 */
@Serializable
@SerialName("ERROR")
data class ErrorMessage(
    val code: ErrorCode,
    val message: String
) : WsMessage()

/**
 * Pong response to ping.
 */
@Serializable
@SerialName("PONG")
data class PongMessage(
    val timestamp: Long = System.currentTimeMillis()
) : WsMessage()

/**
 * Notification that a player's connection status changed.
 */
@Serializable
@SerialName("PLAYER_STATUS")
data class PlayerStatusMessage(
    val playerId: PlayerId,
    val isConnected: Boolean
) : WsMessage()

// ============== Auth Messages ==============

@Serializable
@SerialName("REGISTER")
data class RegisterMessage(
    val username: String,
    val email: String,
    val password: String, // In production should be hashed clientside or SSL
    val avatarId: Int
) : WsMessage()

@Serializable
@SerialName("LOGIN")
data class LoginMessage(
    val email: String,
    val password: String
) : WsMessage()

@Serializable
@SerialName("AUTH_SUCCESS")
data class AuthSuccessMessage(
    val user: UserProfile
) : WsMessage()

@Serializable
data class UserProfile(
    val id: String,
    val username: String,
    val email: String,
    val avatarId: Int
)

// ============== Handover Messages ==============

/**
 * UDP broadcast when a new host takes over.
 */
@Serializable
@SerialName("HOST_ANNOUNCE")
data class HostAnnounceMessage(
    val gameId: String,
    val epoch: Int,
    val hostId: PlayerId,
    val wsUrl: String
) : WsMessage()

/**
 * Request for clients to acknowledge new host.
 */
@Serializable
@SerialName("HANDOVER_INIT")
data class HandoverInitMessage(
    val newHostId: PlayerId,
    val newEpoch: Int,
    val wsUrl: String
) : WsMessage()

/**
 * Client acknowledges handover to new host.
 */
@Serializable
@SerialName("HANDOVER_ACK")
data class HandoverAckMessage(
    val playerId: PlayerId,
    val acknowledged: Boolean
) : WsMessage()

// ============== Error Codes ==============

@Serializable
enum class ErrorCode {
    INVALID_JOIN_CODE,
    GAME_NOT_FOUND,
    GAME_FULL,
    GAME_ALREADY_STARTED,
    PLAYER_NOT_FOUND,
    UNAUTHORIZED,
    VALIDATION_FAILED,
    INTERNAL_ERROR,
    CONNECTION_LOST,
    HANDOVER_IN_PROGRESS
}

// ============== Connection State ==============

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    HANDOVER
}
