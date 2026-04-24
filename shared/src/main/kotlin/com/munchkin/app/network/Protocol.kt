package com.munchkin.app.network

import com.munchkin.app.core.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WebSocket protocol messages for client-server communication.
 */

@Serializable
sealed class WsMessage

@Serializable
@SerialName("CreateGameMessage")
data class CreateGameRequest(
    val playerMeta: PlayerMeta,
    val superMunchkin: Boolean = false,
    val turnTimerSeconds: Int = 0
) : WsMessage()

// ============== Client to server messages ==============

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

// ============== Server to client messages ==============

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
    val gameId: String? = null,
    val epoch: Int = 0,
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

/**
 * Host request to remove a player from the game.
 */
@Serializable
@SerialName("KICK_PLAYER")
data class KickPlayerMessage(
    val targetPlayerId: PlayerId
) : WsMessage()

@Serializable
@SerialName("SWAP_PLAYERS")
data class SwapPlayers(
    val player1: PlayerId,
    val player2: PlayerId
) : WsMessage()

// ============== Error Codes ==============

@Serializable
enum class ErrorCode {
    INVALID_JOIN_CODE,
    GAME_NOT_FOUND,
    UNAUTHORIZED,
    VALIDATION_FAILED,
    CONNECTION_LOST,
    INVALID_DATA
}

// ============== Room lifecycle messages ==============

@Serializable
@SerialName("GAME_OVER")
data class GameOverMessage(
    val gameId: String,
    val winnerId: String
) : WsMessage()

@Serializable
@SerialName("GAME_OVER_RECORDED")
data class GameOverRecordedMessage(
    val gameId: String,
    val winnerId: String
) : WsMessage()

@Serializable
@SerialName("DELETE_GAME")
data class DeleteGameMessage(
    val timestamp: Long = System.currentTimeMillis()
) : WsMessage()

const val GAME_DELETED_BY_HOST_REASON = "deleted_by_host"

@Serializable
@SerialName("GAME_DELETED")
data class GameDeletedMessage(
    val reason: String
) : WsMessage()

// ============== Connection State ==============

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED_PERMANENTLY  // All automatic retry attempts exhausted; requires manual retry
}
