package com.munchkin.app.viewmodel

import com.munchkin.app.core.*
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.network.GameClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Fully local Pass & Play implementation of [SessionConnection].
 * Runs 100% offline using pure [GameEngine] and [CombatCalculator] with a 5-step Undo stack.
 */
internal class OfflineSessionConnection(
    initialState: GameState,
    initialSeat: PlayerId
) : SessionConnection {

    private val engine = GameEngine().apply { loadState(initialState) }
    private val undoStack = ArrayDeque<GameState>()
    private val lock = Any()

    override val rawClient: GameClient? = null

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override val gameState: StateFlow<GameState?> = engine.gameState

    private val _myPlayerId = MutableStateFlow<PlayerId?>(initialSeat)
    override val myPlayerId: StateFlow<PlayerId?> = _myPlayerId.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    override val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _reconnectToken = MutableStateFlow<String?>(null)
    override val reconnectToken: StateFlow<String?> = _reconnectToken.asStateFlow()

    private val _gameDeleted = MutableSharedFlow<String>(extraBufferCapacity = 2)
    override val gameDeleted: SharedFlow<String> = _gameDeleted.asSharedFlow()

    private val _serverTimeOffsetMs = MutableStateFlow(0L)
    override val serverTimeOffsetMs: StateFlow<Long> = _serverTimeOffsetMs.asStateFlow()

    fun switchActiveSeat(playerId: PlayerId) {
        val current = engine.getCurrentState() ?: return
        if (current.players.containsKey(playerId)) {
            _myPlayerId.value = playerId
        }
    }

    override fun nudge() = Unit

    override suspend fun sendEvent(event: GameEvent): Result<Unit> {
        synchronized(lock) {
            val prev = engine.getCurrentState() ?: return Result.failure(IllegalStateException("No game"))
            undoStack.addLast(prev)
            while (undoStack.size > MAX_UNDO) undoStack.removeFirst()

            val effectiveEvent = if (event is CombatEnd && !event.cancelled && prev.combat != null && event.outcome != CombatOutcome.ESCAPE) {
                val calc = CombatCalculator.calculateResult(prev.combat, prev)
                event.copy(
                    outcome = calc.outcome,
                    levelsGained = calc.totalLevels,
                    treasuresGained = calc.totalTreasures,
                    helperLevelsGained = calc.helperLevelsGained
                )
            } else {
                event
            }

            val next = GameEngine.reduce(prev, effectiveEvent).copy(
                seq = prev.seq + 1,
                canUndo = undoStack.isNotEmpty()
            )
            engine.loadState(next)
            if (effectiveEvent is EndTurn && next.turnPlayerId != null) {
                _myPlayerId.value = next.turnPlayerId
            }
        }
        return Result.success(Unit)
    }

    override suspend fun sendUndoLast(): Result<Unit> {
        synchronized(lock) {
            val prev = undoStack.removeLastOrNull() ?: return Result.success(Unit)
            engine.loadState(prev.copy(canUndo = undoStack.isNotEmpty()))
        }
        return Result.success(Unit)
    }

    override suspend fun sendPauseTurnTimer(): Result<Unit> = Result.success(Unit)
    override suspend fun sendResumeTurnTimer(): Result<Unit> = Result.success(Unit)

    override suspend fun kickPlayer(targetPlayerId: PlayerId): Result<Unit> = Result.success(Unit)

    override suspend fun sendTransferHost(targetPlayerId: PlayerId): Result<Unit> = Result.success(Unit)

    override suspend fun sendDeleteGame(): Result<Unit> {
        _connectionState.value = ConnectionState.DISCONNECTED
        return Result.success(Unit)
    }

    override suspend fun sendSwapPlayers(player1: PlayerId, player2: PlayerId): Result<Unit> {
        synchronized(lock) {
            val current = engine.getCurrentState() ?: return Result.success(Unit)
            val order = current.playerList.map { it.playerId }.toMutableList()
            val i1 = order.indexOf(player1)
            val i2 = order.indexOf(player2)
            if (i1 >= 0 && i2 >= 0) {
                order[i1] = player2
                order[i2] = player1
                engine.loadState(current.copy(playerOrder = order, seq = current.seq + 1))
            }
        }
        return Result.success(Unit)
    }

    override suspend fun sendGameOver(gameId: String, winnerId: String?): Result<Unit> {
        synchronized(lock) {
            val current = engine.getCurrentState() ?: return Result.success(Unit)
            engine.loadState(
                current.copy(
                    phase = GamePhase.FINISHED,
                    winnerId = winnerId?.takeIf { it.isNotBlank() }?.let(::PlayerId),
                    combat = null,
                    seq = current.seq + 1
                )
            )
        }
        return Result.success(Unit)
    }

    override suspend fun addMonsterToCatalog(
        serverUrl: String,
        monster: CatalogMonster,
        authToken: String
    ): Result<CatalogMonster> = Result.success(monster.copy(id = UUID.randomUUID().toString()))

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun close() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    companion object {
        private const val MAX_UNDO = 5

        fun create(
            playerNames: List<String>,
            superMunchkin: Boolean = false,
            expansionsEnabled: Boolean = false
        ): OfflineSessionConnection {
            val sanitized = playerNames.map { it.trim().take(20) }.filter { it.isNotEmpty() }.take(6)
            val effective = if (sanitized.size >= 2) sanitized else listOf("Jugador 1", "Jugador 2")
            val players = LinkedHashMap<PlayerId, PlayerState>()
            val order = mutableListOf<PlayerId>()
            effective.forEachIndexed { index, name ->
                val pid = PlayerId("local-${index + 1}")
                order.add(pid)
                players[pid] = PlayerState(
                    playerId = pid,
                    name = name,
                    avatarId = index % 8,
                    gender = if (index % 2 == 0) Gender.M else Gender.F,
                    level = 1,
                    gearBonus = 0,
                    isConnected = true
                )
            }
            val firstId = order.first()
            val state = GameState(
                gameId = GameId("offline-${UUID.randomUUID().toString().take(8)}"),
                joinCode = "LOCAL",
                hostId = firstId,
                players = players,
                phase = GamePhase.IN_GAME,
                turnPlayerId = firstId,
                playerOrder = order,
                settings = GameSettings(
                    maxLevel = if (superMunchkin) 20 else 10,
                    expansionsEnabled = expansionsEnabled
                )
            )
            return OfflineSessionConnection(state, firstId)
        }
    }
}
