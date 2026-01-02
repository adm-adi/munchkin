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
 * Data class for discovered games from server.
 */
@Serializable
data class DiscoveredGame(
    val hostName: String,
    val joinCode: String,
    val playerCount: Int = 1,
    val maxPlayers: Int = 6,
    val wsUrl: String = "",
    val port: Int = 8765
)

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
    HANDOVER_IN_PROGRESS,
    // Auth Errors
    AUTH_FAILED,
    EMAIL_EXISTS,
    REGISTER_FAILED,
    LOGIN_ERROR,
    INVALID_DATA,
    // Catalog Errors
    SEARCH_ERROR,
    ADD_MONSTER_ERROR
}

// ============== Catalog Messages ==============

@Serializable
data class CatalogMonster(
    val id: String = "",
    val name: String,
    val level: Int,
    val modifier: Int = 0,
    val treasures: Int = 1,
    val levels: Int = 1,
    val isUndead: Boolean = false,
    val badStuff: String = "",
    val expansion: String = "base",
    val createdBy: String? = null
)

@Serializable
@SerialName("CATALOG_SEARCH")
data class CatalogSearchRequest(
    val query: String
) : WsMessage()

@Serializable
@SerialName("CATALOG_SEARCH_RESULT")
data class CatalogSearchResult(
    val results: List<CatalogMonster>
) : WsMessage()

@Serializable
@SerialName("CATALOG_ADD")
data class CatalogAddRequest(
    val monster: CatalogMonster,
    val userId: String
) : WsMessage()

@Serializable
@SerialName("CATALOG_ADD_SUCCESS")
data class CatalogAddSuccess(
    val monster: CatalogMonster
) : WsMessage()



// ============== Game History Messages ==============

@Serializable
@SerialName("GAME_OVER")
data class GameOverMessage(
    val gameId: String,
    val winnerId: String
) : WsMessage()

@Serializable
@SerialName("END_TURN")
object EndTurnMessage : WsMessage()

@Serializable
@SerialName("GET_HISTORY")
data class GetHistoryRequest(
    val userId: String
) : WsMessage()

@Serializable
@SerialName("HISTORY_RESULT")
data class HistoryResult(
    val games: List<GameHistoryItem>
) : WsMessage()

@Serializable
@SerialName("GET_LEADERBOARD")
object GetLeaderboardRequest : WsMessage()

@Serializable
@SerialName("LEADERBOARD_RESULT")
data class LeaderboardResult(
    val leaderboard: List<LeaderboardEntry>
) : WsMessage()

@Serializable
data class LeaderboardEntry(
    val id: String,
    val username: String,
    val avatarId: Int,
    val wins: Int
)

@Serializable
data class GameHistoryItem(
    val id: String,
    val endedAt: Long,
    val winnerId: String
)

// ============== Combat Dice Roll Result ==============

@Serializable
@SerialName("COMBAT_DICE_ROLL_RESULT")
data class CombatDiceRollResult(
    val diceRoll: DiceRollInfo
) : WsMessage()

// ============== Connection State ==============

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    HANDOVER
}
