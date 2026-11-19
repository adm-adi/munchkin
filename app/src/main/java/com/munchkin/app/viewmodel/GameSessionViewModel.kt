package com.munchkin.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.R
import com.munchkin.app.core.ActiveCurse
import com.munchkin.app.core.AddCurse
import com.munchkin.app.core.BonusTarget
import com.munchkin.app.core.CharacterClass
import com.munchkin.app.core.CharacterRace
import com.munchkin.app.core.CombatAddBonus
import com.munchkin.app.core.CombatAddHelper
import com.munchkin.app.core.CombatAddMonster
import com.munchkin.app.core.CombatCalculator
import com.munchkin.app.core.CombatEnd
import com.munchkin.app.core.CombatOutcome
import com.munchkin.app.core.CombatRemoveBonus
import com.munchkin.app.core.CombatRemoveHelper
import com.munchkin.app.core.CombatRemoveMonster
import com.munchkin.app.core.CombatSetHelperTreasures
import com.munchkin.app.core.CombatSetModifier
import com.munchkin.app.core.CombatStart
import com.munchkin.app.core.CombatUpdateMonster
import com.munchkin.app.core.ConditionalModifier
import com.munchkin.app.core.DecGear
import com.munchkin.app.core.DecLevel
import com.munchkin.app.core.DiceRollPurpose
import com.munchkin.app.core.EndTurn
import com.munchkin.app.core.GameEngine
import com.munchkin.app.core.GameEvent
import com.munchkin.app.core.GameId
import com.munchkin.app.core.GameLogEntry
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameStart
import com.munchkin.app.core.GameState
import com.munchkin.app.core.Gender
import com.munchkin.app.core.IncGear
import com.munchkin.app.core.IncLevel
import com.munchkin.app.core.LogType
import com.munchkin.app.core.MonsterInstance
import com.munchkin.app.core.PlayerDeath
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerRoll
import com.munchkin.app.core.PlayerState
import com.munchkin.app.core.RemoveCurse
import com.munchkin.app.core.SetClass
import com.munchkin.app.core.SetGender
import com.munchkin.app.core.SetGear
import com.munchkin.app.core.SetHalfBreed
import com.munchkin.app.core.SetRace
import com.munchkin.app.core.SetSuperMunchkin
import com.munchkin.app.core.TempBonus
import com.munchkin.app.data.GameRepository
import com.munchkin.app.data.SavedGame
import com.munchkin.app.data.SessionManager
import com.munchkin.app.di.GameSessionCoordinator
import com.munchkin.app.di.StringResolver
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages the active WebSocket game session: turn actions, combat parity, reconnection,
 * player traits, multi-winner confirmation, and game log updates.
 */
class GameSessionViewModel internal constructor(
    private val sessionStore: SessionStore,
    private val savedGameStore: SavedGameStore,
    private val remoteGateway: RemoteGateway,
    private val sessionCoordinator: GameSessionCoordinator,
    private val stringResolver: StringResolver,
    private val clockMs: () -> Long = System::currentTimeMillis
) : ViewModel() {

    constructor(
        sessionManager: SessionManager,
        gameRepository: GameRepository,
        clientFactory: () -> GameClient,
        sessionCoordinator: GameSessionCoordinator,
        stringResolver: StringResolver
    ) : this(
        sessionStore = SessionManagerStore(sessionManager),
        savedGameStore = RoomSavedGameStore(gameRepository),
        remoteGateway = GameClientRemoteGateway(clientFactory),
        sessionCoordinator = sessionCoordinator,
        stringResolver = stringResolver
    )

    private data class PendingCombatEndCandidate(
        val monsters: List<MonsterInstance>,
        val mainPlayerId: PlayerId,
        val prevMainLevel: Int,
        val prevMainTreasures: Int,
        val expectBadStuff: Boolean
    )

    val gameState: StateFlow<GameState?> = sessionCoordinator.gameState
    val myPlayerId: StateFlow<PlayerId?> = sessionCoordinator.myPlayerId

    val isHost: StateFlow<Boolean> = combine(gameState, myPlayerId) { state, pid ->
        state != null && pid != null && state.hostId == pid
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val connectionState: StateFlow<ConnectionState> = sessionCoordinator.connectionState
    val reconnectAttempt: StateFlow<Int> = sessionCoordinator.reconnectAttempt
    val serverTimeOffsetMs: StateFlow<Long> = sessionCoordinator.serverTimeOffsetMs

    val isReconnectFailed: StateFlow<Boolean> = connectionState
        .map { it == ConnectionState.FAILED_PERMANENTLY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _gameLog = MutableStateFlow<List<GameLogEntry>>(emptyList())
    val gameLog: StateFlow<List<GameLogEntry>> = _gameLog.asStateFlow()

    private val _pendingBadStuff = MutableStateFlow<List<MonsterInstance>>(emptyList())
    val pendingBadStuff: StateFlow<List<MonsterInstance>> = _pendingBadStuff.asStateFlow()

    private val _isCombatResolving = MutableStateFlow(false)
    val isCombatResolving: StateFlow<Boolean> = _isCombatResolving.asStateFlow()

    private val _monsterSearchResults = MutableStateFlow<List<CatalogMonster>>(emptyList())
    val monsterSearchResults: StateFlow<List<CatalogMonster>> = _monsterSearchResults.asStateFlow()

    private val _selectedPlayerId = MutableStateFlow<PlayerId?>(null)
    val selectedPlayer: StateFlow<PlayerState?> = combine(gameState, _selectedPlayerId) { state, pid ->
        pid?.let { state?.players?.get(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _dismissedWinners = MutableStateFlow<Set<PlayerId>>(emptySet())
    private val _hasRecordedGame = MutableStateFlow(false)

    val pendingWinners: StateFlow<List<PlayerState>> = combine(
        gameState,
        isHost,
        _dismissedWinners,
        _hasRecordedGame
    ) { state, host, dismissed, recorded ->
        if (state == null || !host || state.phase != GamePhase.IN_GAME || state.winnerId != null || recorded) {
            emptyList()
        } else {
            state.playerList.filter { player ->
                player.canBeConfirmedWinner(state.settings) && player.playerId !in dismissed
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingWinnerId: StateFlow<PlayerId?> = pendingWinners
        .map { it.firstOrNull()?.playerId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var sessionObserversJob: Job? = null
    private var lastObservedState: GameState? = null
    private var lastConnectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var recordedGameId: GameId? = null
    private var pendingCombatEndCandidate: PendingCombatEndCandidate? = null
    private val lastPhaseActionTimestamps = mutableMapOf<String, Long>()
    private var phaseActionInFlight = false

    init {
        viewModelScope.launch {
            sessionCoordinator.activeSession.collect { session ->
                if (session == null) {
                    cleanupClientObservers(closeClient = false)
                } else {
                    observeSession(session)
                }
            }
        }
    }

    private fun observeSession(session: SessionConnection) {
        sessionObserversJob?.cancel()
        lastObservedState = session.gameState.value ?: sessionCoordinator.gameState.value
        lastConnectionState = session.connectionState.value

        sessionObserversJob = viewModelScope.launch {
            launch {
                session.gameState.collect { newState ->
                    if (newState != null) {
                        handleIncomingGameState(newState)
                    }
                }
            }
            launch {
                session.myPlayerId.collect { pid ->
                    if (pid != null) {
                        sessionCoordinator.updateMyPlayerId(pid)
                    }
                }
            }
            launch {
                session.connectionState.collect { connState ->
                    val prev = lastConnectionState
                    lastConnectionState = connState
                    sessionCoordinator.updateConnectionState(connState)
                    if (connState != ConnectionState.CONNECTED && _isCombatResolving.value) {
                        _isCombatResolving.value = false
                        pendingCombatEndCandidate = null
                    }
                    if (prev == ConnectionState.RECONNECTING && connState == ConnectionState.CONNECTED) {
                        _isCombatResolving.value = false
                        pendingCombatEndCandidate = null
                        sessionCoordinator.emitEvent(GameUiEvent.Reconnected)
                    }
                }
            }
            launch {
                session.reconnectAttempt.collect { attempt ->
                    sessionCoordinator.updateReconnectAttempt(attempt)
                }
            }
            launch {
                session.serverTimeOffsetMs.collect { offset ->
                    sessionCoordinator.updateServerTimeOffsetMs(offset)
                }
            }
            launch {
                session.reconnectToken.collect { token ->
                    val joinCode = gameState.value?.joinCode
                    if (token != null && joinCode != null) {
                        sessionStore.saveReconnectToken(joinCode, token)
                    }
                }
            }
            launch {
                session.errors.collect { rawError ->
                    if (_isCombatResolving.value) {
                        _isCombatResolving.value = false
                        pendingCombatEndCandidate = null
                    }
                    sessionCoordinator.emitEvent(
                        GameUiEvent.ShowError(mapServerErrorMessage(rawError, stringResolver))
                    )
                }
            }
            launch {
                session.gameDeleted.collect {
                    handleRemoteGameDeleted()
                }
            }
        }
    }

    private suspend fun handleIncomingGameState(newState: GameState) {
        val prevState = lastObservedState
        lastObservedState = newState

        if (recordedGameId != null && recordedGameId != newState.gameId) {
            recordedGameId = null
            _hasRecordedGame.value = false
            _dismissedWinners.value = emptySet()
        }

        // Clear dismissed status for any player who dropped below win eligibility so returning to 10 prompts again.
        if (_dismissedWinners.value.isNotEmpty()) {
            _dismissedWinners.update { current ->
                current.filter { pid ->
                    newState.players[pid]?.canBeConfirmedWinner(newState.settings) == true
                }.toSet()
            }
        }

        // Non-optimistic combat end confirmation: only reveal Bad Stuff after server closes combat without a hero win.
        if (prevState?.combat != null && newState.combat == null) {
            _isCombatResolving.value = false
            val candidate = pendingCombatEndCandidate
            pendingCombatEndCandidate = null
            if (candidate != null && candidate.expectBadStuff && candidate.monsters.isNotEmpty()) {
                val updatedMain = newState.players[candidate.mainPlayerId]
                val mainGainedReward = updatedMain != null && (
                    updatedMain.level > candidate.prevMainLevel ||
                        updatedMain.treasures > candidate.prevMainTreasures
                    )
                if (!mainGainedReward) {
                    _pendingBadStuff.value = candidate.monsters
                }
            }
        }

        appendLogDiffs(prevState, newState)
        sessionCoordinator.updateGameState(newState)

        val pid = sessionCoordinator.myPlayerId.value
        if (pid != null) {
            val isHostNow = newState.hostId == pid
            savedGameStore.saveGame(newState, pid, isHostNow)
            sessionCoordinator.setSavedGame(
                SavedGame(
                    gameState = newState,
                    myPlayerId = pid,
                    isHost = isHostNow,
                    savedAt = clockMs()
                )
            )
        }
    }

    private fun appendLogDiffs(prevState: GameState?, newState: GameState) {
        if (prevState == null || prevState.gameId != newState.gameId) return
        val newEntries = mutableListOf<GameLogEntry>()
        for ((playerId, player) in newState.players) {
            val prevPlayer = prevState.players[playerId] ?: continue
            if (player.level > prevPlayer.level) {
                newEntries.add(
                    GameLogEntry(
                        message = stringResolver.get(R.string.log_level_up, player.name, player.level),
                        type = LogType.LEVEL_UP
                    )
                )
            } else if (player.level < prevPlayer.level) {
                newEntries.add(
                    GameLogEntry(
                        message = stringResolver.get(R.string.log_level_down, player.name, player.level),
                        type = LogType.INFO
                    )
                )
            }
        }
        if (prevState.combat != null && newState.combat == null) {
            newEntries.add(
                GameLogEntry(
                    message = stringResolver.get(R.string.log_combat_ended),
                    type = LogType.COMBAT
                )
            )
        }
        if (newEntries.isNotEmpty()) {
            _gameLog.update { current -> (newEntries + current).take(MAX_LOG_ENTRIES) }
        }
    }

    private suspend fun handleRemoteGameDeleted() {
        val deletedGameId = gameState.value?.gameId?.value
        cleanupClientObservers(closeClient = true)
        resetLocalSessionUiState()
        savedGameStore.deleteAllSavedGames()
        sessionCoordinator.setSavedGame(null)
        if (deletedGameId != null) {
            sessionCoordinator.setHostedGames(
                sessionCoordinator.hostedGames.value.filterNot { it.gameId == deletedGameId }
            )
        }
        sessionCoordinator.emitEvent(
            GameUiEvent.ShowMessage(stringResolver.get(R.string.game_deleted_by_host))
        )
    }

    private var monsterSearchJob: Job? = null

    fun nudgeConnection() {
        sessionCoordinator.activeSession.value?.nudge()
    }

    fun checkReconnection(allowColdResume: Boolean = false) {
        val session = sessionCoordinator.activeSession.value
        val state = connectionState.value
        if (session != null && state != ConnectionState.DISCONNECTED) {
            session.nudge()
        } else if (
            allowColdResume &&
            session == null &&
            sessionCoordinator.savedGame.value != null &&
            !sessionCoordinator.isConnectingOrLoading.value
        ) {
            resumeDisconnectedSavedGame()
        }
    }

    fun retryReconnect() {
        val session = sessionCoordinator.activeSession.value
        if (session != null) {
            session.nudge()
        } else {
            resumeDisconnectedSavedGame()
        }
    }

    private fun resumeDisconnectedSavedGame() {
        if (sessionCoordinator.isConnectingOrLoading.value) return
        sessionCoordinator.setConnectingOrLoading(true)
        viewModelScope.launch {
            try {
                val result = GameConnectionSupport.resumeSavedGameSession(
                    sessionStore = sessionStore,
                    savedGameStore = savedGameStore,
                    remoteGateway = remoteGateway,
                    sessionCoordinator = sessionCoordinator,
                    stringResolver = stringResolver
                )
                result.onFailure { err ->
                    sessionCoordinator.emitEvent(
                        GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                sessionCoordinator.setConnectingOrLoading(false)
            }
        }
    }

    // ============== Phase & Lobby/Admin Actions ==============

    fun startGame() {
        if (!isHost.value || gameState.value?.phase != GamePhase.LOBBY) return
        sendGuardedPhaseEvent("START_GAME") { playerId ->
            GameStart(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs()
            )
        }
    }

    fun endTurn() {
        if (gameState.value?.phase != GamePhase.IN_GAME) return
        sendGuardedPhaseEvent("END_TURN") { playerId ->
            EndTurn(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs()
            )
        }
    }

    fun rollDice(result: Int) {
        val clamped = result.coerceIn(1, 6)
        sendPlayerEvent { playerId ->
            PlayerRoll(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId,
                result = clamped,
                purpose = DiceRollPurpose.RANDOM,
                success = false
            )
        }
    }

    fun rollDiceForStart() {
        rollDice((1..6).random())
    }

    fun swapPlayers(player1: PlayerId, player2: PlayerId) {
        if (!isHost.value) return
        val session = sessionCoordinator.activeSession.value ?: return
        viewModelScope.launch {
            try {
                session.sendSwapPlayers(player1, player2).onFailure { err ->
                    sessionCoordinator.emitEvent(
                        GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun transferHost(targetPlayerId: PlayerId) {
        if (!isHost.value || targetPlayerId == myPlayerId.value) return
        val session = sessionCoordinator.activeSession.value ?: return
        viewModelScope.launch {
            try {
                session.sendTransferHost(targetPlayerId).onFailure { err ->
                    sessionCoordinator.emitEvent(
                        GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun kickPlayer(targetPlayerId: PlayerId) {
        if (!isHost.value || targetPlayerId == myPlayerId.value) return
        val session = sessionCoordinator.activeSession.value ?: return
        viewModelScope.launch {
            try {
                session.kickPlayer(targetPlayerId).onFailure { err ->
                    sessionCoordinator.emitEvent(
                        GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun deleteGame() {
        if (!isHost.value) return
        val session = sessionCoordinator.activeSession.value ?: return
        val deletedGameId = gameState.value?.gameId?.value
        viewModelScope.launch {
            try {
                val result = session.sendDeleteGame()
                result.fold(
                    onSuccess = {
                        cleanupClientObservers(closeClient = true)
                        resetLocalSessionUiState()
                        savedGameStore.deleteAllSavedGames()
                        sessionCoordinator.setSavedGame(null)
                        if (deletedGameId != null) {
                            sessionCoordinator.setHostedGames(
                                sessionCoordinator.hostedGames.value.filterNot { it.gameId == deletedGameId }
                            )
                        }
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowSuccess(stringResolver.get(R.string.game_deleted_ok))
                        )
                    },
                    onFailure = { err ->
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun leaveGame() {
        val session = sessionCoordinator.activeSession.value
        viewModelScope.launch {
            try {
                if (session != null) {
                    runCatching { session.disconnect() }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                cleanupClientObservers(closeClient = true)
                resetLocalSessionUiState()
                savedGameStore.deleteAllSavedGames()
                sessionCoordinator.setSavedGame(null)
            }
        }
    }

    // ============== Player Selection & Traits ==============

    fun selectPlayer(playerId: PlayerId?) {
        _selectedPlayerId.value = playerId
    }

    fun incrementLevel(amount: Int = 1) {
        val safeAmount = amount.coerceIn(1, GameEngine.STEP_LIMIT)
        sendPlayerEvent { playerId ->
            IncLevel(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId,
                amount = safeAmount
            )
        }
    }

    fun decrementLevel(amount: Int = 1) {
        val safeAmount = amount.coerceIn(1, GameEngine.STEP_LIMIT)
        sendPlayerEvent { playerId ->
            DecLevel(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId,
                amount = safeAmount
            )
        }
    }

    fun incrementGear(amount: Int = 1) {
        val safeAmount = amount.coerceIn(1, GameEngine.STEP_LIMIT)
        sendPlayerEvent { playerId ->
            IncGear(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId,
                amount = safeAmount
            )
        }
    }

    fun decrementGear(amount: Int = 1) {
        val safeAmount = amount.coerceIn(1, GameEngine.STEP_LIMIT)
        sendPlayerEvent { playerId ->
            DecGear(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId,
                amount = safeAmount
            )
        }
    }

    fun applyDeath() {
        sendPlayerEvent { playerId ->
            PlayerDeath(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId
            )
        }
    }

    fun setCharacterClass(newClass: CharacterClass, isSecondary: Boolean = false) {
        sendPlayerEvent { playerId ->
            SetClass(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId,
                newClass = newClass,
                isSecondary = isSecondary
            )
        }
    }

    fun setCharacterRace(newRace: CharacterRace, isSecondary: Boolean = false) {
        sendPlayerEvent { playerId ->
            SetRace(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId,
                newRace = newRace,
                isSecondary = isSecondary
            )
        }
    }

    fun setHalfBreed(enabled: Boolean) {
        sendPlayerEvent { playerId ->
            SetHalfBreed(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId,
                enabled = enabled
            )
        }
    }

    fun setSuperMunchkin(enabled: Boolean) {
        sendPlayerEvent { playerId ->
            SetSuperMunchkin(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId,
                enabled = enabled
            )
        }
    }

    fun toggleGender() {
        val playerId = myPlayerId.value ?: return
        val currentPlayer = gameState.value?.players?.get(playerId) ?: return
        val nextGender = when (currentPlayer.gender) {
            Gender.M -> Gender.F
            Gender.F -> Gender.NA
            Gender.NA -> Gender.M
        }
        sendPlayerEvent { pid ->
            SetGender(
                eventId = UUID.randomUUID().toString(),
                actorId = pid,
                timestamp = clockMs(),
                targetPlayerId = pid,
                gender = nextGender
            )
        }
    }

    fun addCurse(
        name: String,
        combatPenalty: Int = 0,
        runAwayPenalty: Int = 0,
        combatsRemaining: Int = 0,
        clearOnDeath: Boolean = true
    ) {
        val cleanName = name.trim().take(40)
        if (cleanName.isEmpty()) return
        val curse = ActiveCurse(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            combatPenalty = combatPenalty.coerceIn(-20, 20),
            runAwayPenalty = runAwayPenalty.coerceIn(-5, 5),
            combatsRemaining = combatsRemaining.coerceIn(0, 10),
            clearOnDeath = clearOnDeath
        )
        sendPlayerEvent { pid ->
            AddCurse(
                eventId = UUID.randomUUID().toString(),
                actorId = pid,
                timestamp = clockMs(),
                targetPlayerId = pid,
                curse = curse
            )
        }
    }

    fun removeCurse(curseId: String) {
        sendPlayerEvent { pid ->
            RemoveCurse(
                eventId = UUID.randomUUID().toString(),
                actorId = pid,
                timestamp = clockMs(),
                targetPlayerId = pid,
                curseId = curseId
            )
        }
    }

    fun undoLastAction() {
        val session = sessionCoordinator.activeSession.value ?: return
        viewModelScope.launch {
            session.sendUndoLast().onFailure { err ->
                sessionCoordinator.emitEvent(
                    GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                )
            }
        }
    }

    fun toggleTurnTimerPause() {
        val session = sessionCoordinator.activeSession.value ?: return
        val state = gameState.value ?: return
        val isPaused = state.turnTimerPausedRemainingMs != null
        viewModelScope.launch {
            val res = if (isPaused) session.sendResumeTurnTimer() else session.sendPauseTurnTimer()
            res.onFailure { err ->
                sessionCoordinator.emitEvent(
                    GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                )
            }
        }
    }

    fun switchOfflineSeat(playerId: PlayerId) {
        val offline = sessionCoordinator.activeSession.value as? OfflineSessionConnection ?: return
        offline.switchActiveSeat(playerId)
        sessionCoordinator.updateMyPlayerId(playerId)
    }

    // ============== Combat Actions ==============

    fun startCombat() {
        if (gameState.value?.combat != null) return
        sendGuardedPhaseEvent("START_COMBAT") { playerId ->
            CombatStart(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                mainPlayerId = playerId
            )
        }
    }

    fun addMonster(
        name: String,
        level: Int,
        modifier: Int,
        isUndead: Boolean,
        badStuff: String = "",
        treasures: Int = 1,
        levels: Int = 1,
        conditionalModifiers: List<ConditionalModifier> = emptyList()
    ) {
        if (!canTouchCombat(gameState.value, myPlayerId.value)) return
        val monster = MonsterInstance(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(60),
            baseLevel = level.coerceIn(1, 20),
            flatModifier = modifier.coerceIn(-10, 10),
            treasures = treasures.coerceIn(0, 10),
            levels = levels.coerceIn(1, 5),
            isUndead = isUndead,
            badStuff = badStuff.trim().take(200),
            conditionalModifiers = conditionalModifiers.take(10)
        )
        sendPlayerEvent { playerId ->
            CombatAddMonster(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                monster = monster
            )
        }
    }

    fun updateMonster(monsterId: String, flatModifier: Int) {
        val currentState = gameState.value ?: return
        val combat = currentState.combat ?: return
        if (!canTouchCombat(currentState, myPlayerId.value)) return
        val existing = combat.monsters.firstOrNull { it.id == monsterId } ?: return
        val clamped = flatModifier.coerceIn(-GameEngine.MODIFIER_LIMIT, GameEngine.MODIFIER_LIMIT)

        sendPlayerEvent { playerId ->
            CombatUpdateMonster(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                monster = existing.copy(flatModifier = clamped)
            )
        }
    }

    fun removeMonster(monsterId: String) {
        if (!canTouchCombat(gameState.value, myPlayerId.value)) return
        sendPlayerEvent { playerId ->
            CombatRemoveMonster(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                monsterId = monsterId
            )
        }
    }

    fun addHelper(helperId: PlayerId) {
        if (!canTouchCombat(gameState.value, myPlayerId.value)) return
        sendPlayerEvent { playerId ->
            CombatAddHelper(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                helperId = helperId
            )
        }
    }

    fun removeHelper() {
        if (!canTouchCombat(gameState.value, myPlayerId.value)) return
        sendPlayerEvent { playerId ->
            CombatRemoveHelper(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs()
            )
        }
    }

    fun setHelperTreasureShare(treasures: Int?) {
        val state = gameState.value ?: return
        if (state.combat?.helperPlayerId == null) return
        if (!canTouchCombat(state, myPlayerId.value)) return
        sendPlayerEvent { playerId ->
            CombatSetHelperTreasures(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                treasures = treasures?.coerceIn(0, 10)
            )
        }
    }

    fun addTempBonus(target: BonusTarget, label: String, amount: Int) {
        if (!canTouchCombat(gameState.value, myPlayerId.value)) return
        val clampedAmount = amount.coerceIn(GameEngine.TEMP_COMBAT_BONUS_MIN, GameEngine.TEMP_COMBAT_BONUS_MAX)
        val cleanLabel = label.trim().take(40).ifBlank {
            if (clampedAmount >= 0) "+$clampedAmount" else "$clampedAmount"
        }
        val bonus = TempBonus(
            id = UUID.randomUUID().toString(),
            label = cleanLabel,
            amount = clampedAmount,
            appliesTo = target
        )
        sendPlayerEvent { playerId ->
            CombatAddBonus(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                bonus = bonus
            )
        }
    }

    fun removeTempBonus(bonusId: String) {
        if (!canTouchCombat(gameState.value, myPlayerId.value)) return
        sendPlayerEvent { playerId ->
            CombatRemoveBonus(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                bonusId = bonusId
            )
        }
    }

    fun modifyCombatModifier(target: BonusTarget, delta: Int) {
        val currentState = gameState.value ?: return
        val combat = currentState.combat ?: return
        if (!canTouchCombat(currentState, myPlayerId.value)) return
        val currentValue = when (target) {
            BonusTarget.HEROES -> combat.heroModifier
            BonusTarget.MONSTER -> combat.monsterModifier
        }
        val nextValue = (currentValue + delta).coerceIn(-GameEngine.MODIFIER_LIMIT, GameEngine.MODIFIER_LIMIT)
        sendPlayerEvent { playerId ->
            CombatSetModifier(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                target = target,
                value = nextValue
            )
        }
    }

    fun rollForCombat(
        purpose: DiceRollPurpose = DiceRollPurpose.RUN_AWAY,
        manualResult: Int? = null,
        success: Boolean = false,
        modifier: Int = 0
    ) {
        val result = (manualResult ?: (1..6).random()).coerceIn(1, 6)
        val clampedMod = if (purpose == DiceRollPurpose.RUN_AWAY) {
            modifier.coerceIn(GameEngine.RUN_AWAY_MODIFIER_MIN, GameEngine.RUN_AWAY_MODIFIER_MAX)
        } else {
            0
        }
        sendPlayerEvent { playerId ->
            PlayerRoll(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = clockMs(),
                targetPlayerId = playerId,
                result = result,
                purpose = purpose,
                success = success,
                modifier = clampedMod
            )
        }
    }

    fun endCombat() {
        if (_isCombatResolving.value) return
        val currentState = gameState.value ?: return
        val combat = currentState.combat ?: return
        val actorId = myPlayerId.value ?: return
        if (!canTouchCombat(currentState, actorId)) return

        val result = CombatCalculator.calculateResult(combat, currentState)
        val mainPlayer = currentState.players[combat.mainPlayerId]
        pendingCombatEndCandidate = PendingCombatEndCandidate(
            monsters = combat.monsters,
            mainPlayerId = combat.mainPlayerId,
            prevMainLevel = mainPlayer?.level ?: 1,
            prevMainTreasures = mainPlayer?.treasures ?: 0,
            expectBadStuff = result.outcome == CombatOutcome.LOSE
        )
        markCombatResolving()

        sendCombatEndEvent(
            CombatEnd(
                eventId = UUID.randomUUID().toString(),
                actorId = actorId,
                timestamp = clockMs(),
                outcome = result.outcome,
                levelsGained = if (result.outcome == CombatOutcome.WIN) result.totalLevels else 0,
                treasuresGained = if (result.outcome == CombatOutcome.WIN) result.totalTreasures else 0,
                helperLevelsGained = if (result.outcome == CombatOutcome.WIN) result.helperLevelsGained else 0,
                expectedSeq = currentState.seq,
                cancelled = false
            )
        )
    }

    fun resolveRunAway(success: Boolean) {
        if (_isCombatResolving.value) return
        val currentState = gameState.value ?: return
        val combat = currentState.combat ?: return
        val actorId = myPlayerId.value ?: return
        if (!canTouchCombat(currentState, actorId)) return

        val mainPlayer = currentState.players[combat.mainPlayerId]
        val outcome = if (success) CombatOutcome.ESCAPE else CombatOutcome.LOSE
        pendingCombatEndCandidate = PendingCombatEndCandidate(
            monsters = combat.monsters,
            mainPlayerId = combat.mainPlayerId,
            prevMainLevel = mainPlayer?.level ?: 1,
            prevMainTreasures = mainPlayer?.treasures ?: 0,
            expectBadStuff = !success
        )
        markCombatResolving()

        sendCombatEndEvent(
            CombatEnd(
                eventId = UUID.randomUUID().toString(),
                actorId = actorId,
                timestamp = clockMs(),
                outcome = outcome,
                levelsGained = 0,
                treasuresGained = 0,
                helperLevelsGained = 0,
                expectedSeq = currentState.seq,
                cancelled = false
            )
        )
    }

    fun cancelCombat() {
        if (_isCombatResolving.value) return
        val currentState = gameState.value ?: return
        if (currentState.combat == null) return
        val actorId = myPlayerId.value ?: return
        if (!canTouchCombat(currentState, actorId)) return

        pendingCombatEndCandidate = null
        markCombatResolving()

        sendCombatEndEvent(
            CombatEnd(
                eventId = UUID.randomUUID().toString(),
                actorId = actorId,
                timestamp = clockMs(),
                outcome = CombatOutcome.ESCAPE,
                levelsGained = 0,
                treasuresGained = 0,
                helperLevelsGained = 0,
                expectedSeq = currentState.seq,
                cancelled = true
            )
        )
    }

    private fun markCombatResolving() {
        _isCombatResolving.value = true
    }

    fun dismissBadStuff() {
        _pendingBadStuff.value = emptyList()
    }

    // ============== Combat Monster Search & Global Creation ==============

    fun searchMonsters(query: String) {
        monsterSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _monsterSearchResults.value = emptyList()
            return
        }
        monsterSearchJob = viewModelScope.launch {
            try {
                val result = remoteGateway.searchMonsters(
                    serverUrl = ServerConfig.WS_URL,
                    query = trimmed,
                    expansion = null,
                    limit = 20,
                    offset = 0
                )
                result.fold(
                    onSuccess = { monsters -> _monsterSearchResults.value = monsters },
                    onFailure = { err ->
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun requestCreateGlobalMonster(
        name: String,
        level: Int,
        modifier: Int,
        isUndead: Boolean,
        treasures: Int,
        levels: Int,
        badStuff: String
    ) {
        val token = sessionStore.getAuthToken() ?: return
        val monster = CatalogMonster(
            name = name.trim().take(80),
            level = level.coerceIn(1, 20),
            modifier = modifier.coerceIn(-10, 10),
            treasures = treasures.coerceIn(0, 10),
            levels = levels.coerceIn(1, 5),
            isUndead = isUndead,
            badStuff = badStuff.trim().take(200),
            expansion = "custom"
        )
        viewModelScope.launch {
            try {
                val session = sessionCoordinator.activeSession.value
                val result = if (session != null) {
                    session.addMonsterToCatalog(ServerConfig.WS_URL, monster, token)
                } else {
                    remoteGateway.addMonsterToCatalog(ServerConfig.WS_URL, monster, token)
                }
                result.fold(
                    onSuccess = { created ->
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowSuccess(stringResolver.get(R.string.monster_created, created.name))
                        )
                    },
                    onFailure = { err ->
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    // ============== Win Confirmation & Game Over Recording ==============

    fun confirmWin(winnerId: PlayerId) {
        if (!isHost.value) return
        val currentGameId = gameState.value?.gameId ?: return
        recordGameOver(currentGameId.value, winnerId)
    }

    fun dismissWinConfirmation(playerId: PlayerId? = null) {
        val currentCandidates = pendingWinners.value
        val toDismiss = if (playerId != null) {
            setOf(playerId)
        } else {
            currentCandidates.map { it.playerId }.toSet()
        }
        if (toDismiss.isNotEmpty()) {
            _dismissedWinners.update { it + toDismiss }
        }
    }

    fun recordGameOver(winnerId: PlayerId?) {
        val currentGameId = gameState.value?.gameId?.value ?: return
        recordGameOver(currentGameId, winnerId)
    }

    private fun recordGameOver(gameId: String, winnerId: PlayerId?) {
        if (_hasRecordedGame.value && recordedGameId?.value == gameId) return
        val session = sessionCoordinator.activeSession.value ?: return
        viewModelScope.launch {
            try {
                val result = session.sendGameOver(gameId, winnerId?.value)
                result.fold(
                    onSuccess = {
                        recordedGameId = GameId(gameId)
                        _hasRecordedGame.value = true
                    },
                    onFailure = { err ->
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    // ============== Internal Helpers ==============

    private fun sendGuardedPhaseEvent(actionKey: String, buildEvent: (PlayerId) -> GameEvent) {
        val now = clockMs()
        val last = lastPhaseActionTimestamps[actionKey] ?: 0L
        if (phaseActionInFlight || (now - last) < PHASE_ACTION_COOLDOWN_MS) return
        val playerId = myPlayerId.value ?: return
        val session = sessionCoordinator.activeSession.value ?: return

        lastPhaseActionTimestamps[actionKey] = now
        phaseActionInFlight = true
        viewModelScope.launch {
            try {
                session.sendEvent(buildEvent(playerId)).onFailure { err ->
                    sessionCoordinator.emitEvent(
                        GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                phaseActionInFlight = false
            }
        }
    }

    private fun sendCombatEndEvent(event: CombatEnd) {
        val session = sessionCoordinator.activeSession.value
        if (session == null) {
            _isCombatResolving.value = false
            pendingCombatEndCandidate = null
            return
        }
        viewModelScope.launch {
            try {
                session.sendEvent(event).onFailure { err ->
                    _isCombatResolving.value = false
                    pendingCombatEndCandidate = null
                    sessionCoordinator.emitEvent(
                        GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                    )
                }
            } catch (e: CancellationException) {
                _isCombatResolving.value = false
                pendingCombatEndCandidate = null
                throw e
            }
        }
    }

    private fun sendPlayerEvent(buildEvent: (PlayerId) -> GameEvent) {
        val playerId = myPlayerId.value ?: return
        val session = sessionCoordinator.activeSession.value ?: return
        viewModelScope.launch {
            try {
                session.sendEvent(buildEvent(playerId)).onFailure { err ->
                    sessionCoordinator.emitEvent(
                        GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun cleanupClientObservers(closeClient: Boolean) {
        sessionObserversJob?.cancel()
        sessionObserversJob = null
        if (closeClient) {
            sessionCoordinator.clearActiveSession(closeClient = true)
        }
    }

    private fun resetLocalSessionUiState() {
        lastObservedState = null
        lastConnectionState = ConnectionState.DISCONNECTED
        pendingCombatEndCandidate = null
        _isCombatResolving.value = false
        _pendingBadStuff.value = emptyList()
        _gameLog.value = emptyList()
        _monsterSearchResults.value = emptyList()
        _selectedPlayerId.value = null
        _dismissedWinners.value = emptySet()
        _hasRecordedGame.value = false
        recordedGameId = null
    }

    override fun onCleared() {
        super.onCleared()
        cleanupClientObservers(closeClient = true)
    }

    companion object {
        private const val PHASE_ACTION_COOLDOWN_MS = 400L
        private const val MAX_LOG_ENTRIES = 50
    }
}
