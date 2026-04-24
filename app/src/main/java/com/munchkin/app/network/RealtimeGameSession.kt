package com.munchkin.app.network

import com.munchkin.app.core.GameEvent
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerMeta
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface RealtimeSession {
    val connectionState: StateFlow<ConnectionState>
    val gameState: StateFlow<GameState?>
    val myPlayerId: StateFlow<PlayerId?>
    val errors: SharedFlow<String>
    val gameDeleted: SharedFlow<String>
    val reconnectAttempt: StateFlow<Int>

    fun isConnected(): Boolean

    suspend fun createGame(
        serverUrl: String,
        playerMeta: PlayerMeta,
        superMunchkin: Boolean = false,
        turnTimerSeconds: Int = 0
    ): Result<GameState>

    suspend fun connect(
        wsUrl: String,
        joinCode: String,
        playerMeta: PlayerMeta
    ): Result<GameState>

    suspend fun sendEvent(event: GameEvent): Result<Unit>
    suspend fun kickPlayer(targetPlayerId: PlayerId): Result<Unit>
    suspend fun sendDeleteGame(): Result<Unit>
    suspend fun disconnect()

    suspend fun sendGameOver(
        gameId: String,
        winnerId: String
    ): Result<Unit>

    suspend fun sendSwapPlayers(player1: PlayerId, player2: PlayerId): Result<Unit>
}

fun interface RealtimeSessionFactory {
    fun create(): RealtimeSession
}

object DefaultRealtimeSessionFactory : RealtimeSessionFactory {
    override fun create(): RealtimeSession = RealtimeGameSession()
}

class RealtimeGameSession(
    private val client: GameClient = GameClient()
) : RealtimeSession {
    override val connectionState: StateFlow<ConnectionState> = client.connectionState
    override val gameState: StateFlow<GameState?> = client.gameState
    override val myPlayerId: StateFlow<PlayerId?> = client.myPlayerId
    override val errors: SharedFlow<String> = client.errors
    override val gameDeleted: SharedFlow<String> = client.gameDeleted
    override val reconnectAttempt: StateFlow<Int> = client.reconnectAttempt

    override fun isConnected(): Boolean = client.isConnected()

    override suspend fun createGame(
        serverUrl: String,
        playerMeta: PlayerMeta,
        superMunchkin: Boolean,
        turnTimerSeconds: Int
    ): Result<GameState> = client.createGame(serverUrl, playerMeta, superMunchkin, turnTimerSeconds)

    override suspend fun connect(
        wsUrl: String,
        joinCode: String,
        playerMeta: PlayerMeta
    ): Result<GameState> = client.connect(wsUrl, joinCode, playerMeta)

    override suspend fun sendEvent(event: GameEvent): Result<Unit> = client.sendEvent(event)

    override suspend fun kickPlayer(targetPlayerId: PlayerId): Result<Unit> = client.kickPlayer(targetPlayerId)

    override suspend fun sendDeleteGame(): Result<Unit> = client.sendDeleteGame()

    override suspend fun disconnect() {
        client.disconnect()
    }

    override suspend fun sendGameOver(
        gameId: String,
        winnerId: String
    ): Result<Unit> = client.sendGameOver(gameId, winnerId)

    override suspend fun sendSwapPlayers(player1: PlayerId, player2: PlayerId): Result<Unit> {
        return client.sendSwapPlayers(player1, player2)
    }
}
