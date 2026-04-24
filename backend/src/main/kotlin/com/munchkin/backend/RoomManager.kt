package com.munchkin.backend

import com.munchkin.app.core.CombatEnd
import com.munchkin.app.core.EndTurn
import com.munchkin.app.core.GameEnd
import com.munchkin.app.core.GameEngine
import com.munchkin.app.core.GameEvent
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerMeta
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerJoin
import com.munchkin.app.core.PlayerLeave
import com.munchkin.app.core.SwapPlayers as SwapPlayersEvent
import com.munchkin.app.core.ValidationResult
import com.munchkin.app.network.CreateGameRequest
import com.munchkin.app.network.ErrorCode
import com.munchkin.app.network.ErrorMessage
import com.munchkin.app.network.GAME_DELETED_BY_HOST_REASON
import com.munchkin.app.network.GameDeletedMessage
import com.munchkin.app.network.GameOverMessage
import com.munchkin.app.network.HostedGame
import com.munchkin.app.network.KickPlayerMessage
import com.munchkin.app.network.OpenGameSummary
import com.munchkin.app.network.StateSnapshotMessage
import com.munchkin.app.network.SwapPlayers
import com.munchkin.app.network.WelcomeMessage
import com.munchkin.app.network.WsMessage
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private data class ConnectionRef(
    val joinCode: String,
    val playerId: PlayerId
)

private data class ParticipantRecord(
    val userId: String?,
    var username: String,
    var avatarId: Int
)

private data class RoomSession(
    val engine: GameEngine,
    val createdAt: Long,
    val hostUserId: String?,
    val participants: MutableMap<PlayerId, ParticipantRecord> = ConcurrentHashMap(),
    var timerJob: Job? = null
)

class RoomManager(
    private val persistFinishedGame: (RecordedGame) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rooms = ConcurrentHashMap<String, RoomSession>()
    private val sockets = ConcurrentHashMap<DefaultWebSocketServerSession, ConnectionRef>()
    private val roomSockets = ConcurrentHashMap<String, MutableMap<PlayerId, DefaultWebSocketServerSession>>()

    suspend fun createRoom(
        socket: DefaultWebSocketServerSession,
        request: CreateGameRequest
    ): WelcomeMessage {
        val engine = GameEngine()
        val initialState = engine.createGame(request.playerMeta)
        engine.loadState(
            initialState.copy(
                settings = initialState.settings.copy(
                    maxLevel = if (request.superMunchkin) 20 else initialState.settings.maxLevel,
                    turnTimerSeconds = request.turnTimerSeconds
                )
            )
        )

        val state = currentState(engine)
        val room = RoomSession(
            engine = engine,
            createdAt = clock(),
            hostUserId = request.playerMeta.userId
        )
        room.participants[request.playerMeta.playerId] = ParticipantRecord(
            userId = request.playerMeta.userId,
            username = request.playerMeta.name,
            avatarId = request.playerMeta.avatarId
        )
        rooms[state.joinCode] = room
        roomSockets[state.joinCode] = ConcurrentHashMap<PlayerId, DefaultWebSocketServerSession>().apply {
            put(request.playerMeta.playerId, socket)
        }
        sockets[socket] = ConnectionRef(state.joinCode, request.playerMeta.playerId)
        return WelcomeMessage(state, request.playerMeta.playerId)
    }

    suspend fun joinRoom(
        socket: DefaultWebSocketServerSession,
        joinCode: String,
        playerMeta: PlayerMeta,
        lastKnownIp: String?
    ): WelcomeMessage? {
        val room = rooms[joinCode] ?: return null
        val state = currentState(room.engine)
        if (state.phase != GamePhase.LOBBY && state.players[playerMeta.playerId] == null) {
            return null
        }

        if (state.players[playerMeta.playerId] == null) {
            val joinEvent = PlayerJoin(
                eventId = UUID.randomUUID().toString(),
                actorId = playerMeta.playerId,
                timestamp = clock(),
                playerMeta = playerMeta,
                lastKnownIp = lastKnownIp
            )
            when (room.engine.processEvent(joinEvent)) {
                is ValidationResult.Error -> return null
                is ValidationResult.Success -> Unit
            }
            room.participants[playerMeta.playerId] = ParticipantRecord(
                userId = playerMeta.userId,
                username = playerMeta.name,
                avatarId = playerMeta.avatarId
            )
        }

        roomSockets.computeIfAbsent(joinCode) { ConcurrentHashMap() }[playerMeta.playerId] = socket
        sockets[socket] = ConnectionRef(joinCode, playerMeta.playerId)
        updateConnectionState(room, playerMeta.playerId, true)
        broadcastSnapshot(joinCode)
        return WelcomeMessage(currentState(room.engine), playerMeta.playerId)
    }

    fun openGames(): List<OpenGameSummary> {
        return rooms.values.mapNotNull { room ->
            val state = currentState(room.engine)
            if (state.phase != GamePhase.LOBBY || state.players.size >= 6) {
                null
            } else {
                val host = state.players[state.hostId]
                OpenGameSummary(
                    joinCode = state.joinCode,
                    hostName = host?.name ?: "Host",
                    playerCount = state.players.size,
                    maxPlayers = 6,
                    createdAt = state.createdAt
                )
            }
        }.sortedBy { it.createdAt }
    }

    fun hostedGamesFor(userId: String): List<HostedGame> {
        return rooms.values.mapNotNull { room ->
            if (room.hostUserId != userId) {
                null
            } else {
                val state = currentState(room.engine)
                HostedGame(
                    gameId = state.gameId.value,
                    joinCode = state.joinCode,
                    playerCount = state.players.size,
                    phase = state.phase.name,
                    createdAt = state.createdAt
                )
            }
        }.sortedByDescending { it.createdAt }
    }

    suspend fun deleteHostedGame(userId: String, gameId: String): Boolean {
        val entry = rooms.entries.firstOrNull {
            val state = currentState(it.value.engine)
            it.value.hostUserId == userId && state.gameId.value == gameId
        } ?: return false

        removeRoom(entry.key)
        return true
    }

    suspend fun handleEvent(
        socket: DefaultWebSocketServerSession,
        event: GameEvent
    ): ErrorMessage? {
        val connection = sockets[socket] ?: return ErrorMessage(ErrorCode.CONNECTION_LOST, "No active room connection")
        val room = rooms[connection.joinCode] ?: return ErrorMessage(ErrorCode.GAME_NOT_FOUND, "Game not found")

        val result = room.engine.processEvent(event)
        return when (result) {
            is ValidationResult.Error -> ErrorMessage(ErrorCode.VALIDATION_FAILED, result.message)
            is ValidationResult.Success -> {
                if (event is GameEnd) {
                    finishRoom(room)
                }
                rescheduleTurnTimer(connection.joinCode)
                broadcastSnapshot(connection.joinCode)
                null
            }
        }
    }

    suspend fun handleSwapPlayers(
        socket: DefaultWebSocketServerSession,
        message: SwapPlayers
    ): ErrorMessage? {
        val connection = sockets[socket] ?: return ErrorMessage(ErrorCode.CONNECTION_LOST, "No active room connection")
        val room = rooms[connection.joinCode] ?: return ErrorMessage(ErrorCode.GAME_NOT_FOUND, "Game not found")
        val state = currentState(room.engine)
        if (state.hostId != connection.playerId) {
            return ErrorMessage(ErrorCode.UNAUTHORIZED, "Only the host can reorder players")
        }

        val event = SwapPlayersEvent(
            eventId = UUID.randomUUID().toString(),
            actorId = connection.playerId,
            timestamp = clock(),
            targetPlayerId = message.player1,
            otherPlayerId = message.player2
        )
        return handleEvent(socket, event)
    }

    suspend fun handleKickPlayer(
        socket: DefaultWebSocketServerSession,
        message: KickPlayerMessage
    ): ErrorMessage? {
        val connection = sockets[socket] ?: return ErrorMessage(ErrorCode.CONNECTION_LOST, "No active room connection")
        val room = rooms[connection.joinCode] ?: return ErrorMessage(ErrorCode.GAME_NOT_FOUND, "Game not found")
        val state = currentState(room.engine)
        if (state.hostId != connection.playerId) {
            return ErrorMessage(ErrorCode.UNAUTHORIZED, "Only the host can kick players")
        }

        val leaveEvent = PlayerLeave(
            eventId = UUID.randomUUID().toString(),
            actorId = message.targetPlayerId,
            timestamp = clock()
        )
        val result = room.engine.processEvent(leaveEvent)
        return when (result) {
            is ValidationResult.Error -> ErrorMessage(ErrorCode.VALIDATION_FAILED, result.message)
            is ValidationResult.Success -> {
                room.participants.remove(message.targetPlayerId)
                roomSockets[connection.joinCode]?.remove(message.targetPlayerId)
                broadcastSnapshot(connection.joinCode)
                null
            }
        }
    }

    suspend fun deleteCurrentRoom(socket: DefaultWebSocketServerSession): Boolean {
        val connection = sockets[socket] ?: return false
        return removeRoom(connection.joinCode)
    }

    suspend fun finishCurrentRoom(
        socket: DefaultWebSocketServerSession,
        message: GameOverMessage
    ): ErrorMessage? {
        val connection = sockets[socket] ?: return ErrorMessage(ErrorCode.CONNECTION_LOST, "No active room connection")
        val room = rooms[connection.joinCode] ?: return ErrorMessage(ErrorCode.GAME_NOT_FOUND, "Game not found")
        val state = currentState(room.engine)
        if (state.gameId.value != message.gameId) {
            return ErrorMessage(ErrorCode.GAME_NOT_FOUND, "Game not found")
        }
        if (state.hostId != connection.playerId) {
            return ErrorMessage(ErrorCode.UNAUTHORIZED, "Only the host can confirm a winner")
        }

        val winnerId = PlayerId(message.winnerId)
        if (!state.players.containsKey(winnerId)) {
            return ErrorMessage(ErrorCode.VALIDATION_FAILED, "Winner not found")
        }

        val event = GameEnd(
            eventId = UUID.randomUUID().toString(),
            actorId = state.hostId,
            timestamp = clock(),
            winnerId = winnerId
        )
        return when (val result = room.engine.processEvent(event)) {
            is ValidationResult.Error -> ErrorMessage(ErrorCode.VALIDATION_FAILED, result.message)
            is ValidationResult.Success -> {
                finishRoom(room)
                rescheduleTurnTimer(connection.joinCode)
                broadcastSnapshot(connection.joinCode)
                null
            }
        }
    }

    suspend fun disconnect(socket: DefaultWebSocketServerSession) {
        val connection = sockets.remove(socket) ?: return
        val room = rooms[connection.joinCode] ?: return
        roomSockets[connection.joinCode]?.remove(connection.playerId)
        updateConnectionState(room, connection.playerId, false)
        broadcastSnapshot(connection.joinCode)
        rescheduleTurnTimer(connection.joinCode)
    }

    private fun updateConnectionState(room: RoomSession, playerId: PlayerId, isConnected: Boolean) {
        val state = currentState(room.engine)
        val player = state.players[playerId] ?: return
        room.engine.loadState(
            state.copy(
                seq = state.seq + 1,
                players = state.players + (playerId to player.copy(isConnected = isConnected))
            )
        )
    }

    private suspend fun broadcastSnapshot(joinCode: String) {
        val room = rooms[joinCode] ?: return
        val state = currentState(room.engine)
        val message = StateSnapshotMessage(state, state.seq)
        val payload = json.encodeToString<com.munchkin.app.network.WsMessage>(message)
        roomSockets[joinCode]?.values?.forEach { session ->
            session.send(Frame.Text(payload))
        }
    }

    private fun rescheduleTurnTimer(joinCode: String) {
        val room = rooms[joinCode] ?: return
        room.timerJob?.cancel()
        room.timerJob = null

        val state = currentState(room.engine)
        val turnPlayerId = state.turnPlayerId ?: return
        if (state.phase != GamePhase.IN_GAME || state.settings.turnTimerSeconds <= 0) {
            if (state.turnEndsAt != null) {
                room.engine.loadState(state.copy(turnEndsAt = null))
            }
            return
        }

        val turnPlayer = state.players[turnPlayerId] ?: return
        if (!turnPlayer.isConnected) {
            return
        }

        val deadline = clock() + state.settings.turnTimerSeconds * 1000L
        room.engine.loadState(state.copy(turnEndsAt = deadline))
        scope.launch {
            broadcastSnapshot(joinCode)
        }

        room.timerJob = scope.launch {
            delay(state.settings.turnTimerSeconds * 1000L)
            val latest = currentState(room.engine)
            val currentPlayerId = latest.turnPlayerId ?: return@launch
            val timeoutEvent = EndTurn(
                eventId = UUID.randomUUID().toString(),
                actorId = currentPlayerId,
                timestamp = clock()
            )
            when (room.engine.processEvent(timeoutEvent)) {
                is ValidationResult.Success -> {
                    broadcastSnapshot(joinCode)
                    rescheduleTurnTimer(joinCode)
                }
                is ValidationResult.Error -> Unit
            }
        }
    }

    private fun finishRoom(room: RoomSession) {
        val state = currentState(room.engine)
        persistFinishedGame(
            RecordedGame(
                id = state.gameId.value,
                joinCode = state.joinCode,
                winnerUserId = state.winnerId?.let { room.participants[it]?.userId },
                startedAt = state.createdAt,
                endedAt = clock(),
                participants = room.participants.map { (playerId, participant) ->
                    RecordedParticipant(
                        userId = participant.userId,
                        playerId = playerId.value,
                        username = participant.username,
                        avatarId = participant.avatarId
                    )
                }
            )
        )
    }

    private suspend fun removeRoom(joinCode: String): Boolean {
        val room = rooms.remove(joinCode) ?: return false
        room.timerJob?.cancel()
        val deletedPayload = json.encodeToString<WsMessage>(
            GameDeletedMessage(reason = GAME_DELETED_BY_HOST_REASON)
        )
        roomSockets.remove(joinCode)?.values?.forEach { session ->
            runCatching { session.send(Frame.Text(deletedPayload)) }
            sockets.remove(session)
        }
        return true
    }

    private fun currentState(engine: GameEngine): GameState {
        return requireNotNull(engine.gameState.value) { "Room state is not initialized" }
    }
}
