package com.munchkin.app.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.ui.components.DebugLogManager as DLog
import com.munchkin.app.AppConfig
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R
import com.munchkin.app.core.*
import com.munchkin.app.data.GameRepository
import com.munchkin.app.data.PlayerIdentityStore
import com.munchkin.app.data.SavedGame
import com.munchkin.app.data.SavedGameStore
import com.munchkin.app.data.SessionManager
import com.munchkin.app.network.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.munchkin.app.network.DiscoveredGame

/**
 * Main ViewModel managing game state and network operations.
 * Handles both host and client roles.
 */
class GameViewModel(
    private val savedGameStore: SavedGameStore,
    private val playerIdentityStore: PlayerIdentityStore,
    private val realtimeSessionFactory: RealtimeSessionFactory,
    private val stateTransitionAnalyzer: GameStateTransitionAnalyzer,
    private val eventFactory: GameEventFactory,
    private val playerIdFactory: PlayerIdFactory,
    private val logger: GameLogger,
    private val textProvider: GameTextProvider,
    private val friendlyErrorMapper: FriendlyErrorMapper
) : ViewModel() {
    constructor() : this(createDefaultGameViewModelDependencies())

    private constructor(dependencies: GameViewModelDependencies) : this(
        savedGameStore = dependencies.savedGameStore,
        playerIdentityStore = dependencies.playerIdentityStore,
        realtimeSessionFactory = dependencies.realtimeSessionFactory,
        stateTransitionAnalyzer = dependencies.stateTransitionAnalyzer,
        eventFactory = dependencies.eventFactory,
        playerIdFactory = dependencies.playerIdFactory,
        logger = dependencies.logger,
        textProvider = dependencies.textProvider,
        friendlyErrorMapper = dependencies.friendlyErrorMapper
    )

    // ============== State ==============
    
    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()
    
    private val _events = MutableSharedFlow<GameUiEvent>()
    val events: SharedFlow<GameUiEvent> = _events.asSharedFlow()
    
    private val _gameLog = MutableStateFlow<List<GameLogEntry>>(emptyList())
    val gameLog: StateFlow<List<GameLogEntry>> = _gameLog.asStateFlow()


    
    private val _savedGame = MutableStateFlow<SavedGame?>(null)
    val savedGame: StateFlow<SavedGame?> = _savedGame.asStateFlow()
    
    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()
    
    // ============== Game Components ==============
    
    private var gameClient: RealtimeSession? = null
    
    private var myPlayerId: PlayerId? = null
    private var isHost: Boolean = false
    
    // ============== Initialization ==============
    
    init {
        initData()
    }
    
    private fun initData() {
        try {
            // Load Saved Game
            viewModelScope.launch {
                savedGameStore.getLatestSavedGame().collect { saved ->
                    _savedGame.value = saved
                }
            }
            
        } catch (e: Exception) {
            logger.error("Failed to init data", e)
        }
    }

    /**
     * Resume a saved game.
     */
    fun resumeSavedGame(userProfile: UserProfile?) {
        val saved = _savedGame.value ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null
                )
            }
            
            myPlayerId = saved.myPlayerId
            isHost = saved.isHost
            
            // Reconnect to remote server (both host and client use remote server)
            try {
                val player = saved.gameState.players[saved.myPlayerId]
                val playerMeta = PlayerMeta(
                    playerId = saved.myPlayerId,
                    name = player?.name ?: appString(R.string.player_fallback),
                    avatarId = player?.avatarId ?: 0,
                    gender = player?.gender ?: Gender.M,
                    userId = userProfile?.id
                )
                
                val client = realtimeSessionFactory.create()
                val result = client.connect(AppConfig.SERVER_URL, saved.gameState.joinCode, playerMeta)
                
                if (result.isFailure) {
                    val friendlyError = friendlyErrorMapper.map(result.exceptionOrNull())
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = friendlyError
                        )
                    }
                    return@launch
                }
                
                gameClient = client
                val gameState = result.getOrNull()
                val restoredState = gameState ?: saved.gameState
                
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        gameState = restoredState,
                        myPlayerId = saved.myPlayerId,
                        isHost = saved.isHost
                    )
                }
                _events.emit(GameUiEvent.Navigate(destinationFor(restoredState)))

                // Observe game state changes from client
                observeClientState()

            } catch (e: Exception) {
                val friendlyError = friendlyErrorMapper.map(e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = friendlyError
                    )
                }
            }
        }
    }
    
    /**
     * Check connection and reconnect if needed (e.g. on app resume).
     */
    fun checkReconnection(userProfile: UserProfile?) {
        val client = gameClient
        val saved = _savedGame.value

        if (saved != null && (client == null || !client.isConnected() ||
                client.connectionState.value == ConnectionState.FAILED_PERMANENTLY)) {
            logger.debug("Auto-reconnecting to saved game...")
            resumeSavedGame(userProfile)
        }
    }

    /**
     * Manual retry after FAILED_PERMANENTLY — resets the flag and attempts reconnect.
     */
    fun retryReconnect(userProfile: UserProfile?) {
        _uiState.update { it.copy(isReconnectFailed = false) }
        resumeSavedGame(userProfile)
    }
    
    /**
     * Delete saved game.
     */
    fun deleteSavedGame(userProfile: UserProfile?) {
        val saved = _savedGame.value ?: return
        
        viewModelScope.launch {
            // If host, try to tell server to delete
            if (saved.isHost) {
                _uiState.update { it.copy(isLoading = true) }
                try {
                    // Temporary connection to delete
                    val client = realtimeSessionFactory.create()
                    
                    val playerMeta = PlayerMeta(
                        playerId = saved.myPlayerId,
                        name = appString(R.string.host), // Name doesn't matter for this op validation
                        avatarId = 0,
                        gender = Gender.M,
                        userId = userProfile?.id
                    )
                    
                    val connectResult = client.connect(AppConfig.SERVER_URL, saved.gameState.joinCode, playerMeta)
                    if (connectResult.isSuccess) {
                        client.sendDeleteGame()
                        // Wait a bit for server to process broadcast
                        kotlinx.coroutines.delay(500)
                        client.disconnect()
                    }
                } catch (e: Exception) {
                    logger.error("Failed to delete on server: ${e.message}")
                }
            }
            
            // Delete locally
            savedGameStore.deleteAllSavedGames()
            _savedGame.value = null
            _uiState.update { it.copy(isLoading = false, error = null) }
        }
    }
    
    /**
     * Clear error state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    /**
     * Create a new game as host.
     */
    fun createGame(
        name: String,
        avatarId: Int,
        gender: Gender,
        timerSeconds: Int = 0,
        superMunchkin: Boolean = false,
        userProfile: UserProfile? = null
    ) {
        logger.debug("createGame called: name=$name, avatarId=$avatarId, gender=$gender, timer=$timerSeconds, superMunchkin=$superMunchkin")
        viewModelScope.launch {
            try {
                logger.debug("Setting loading state...")
                _uiState.update { it.copy(isLoading = true, error = null) }
                
                // Generate player ID
                val playerId = playerIdFactory.create()
                myPlayerId = playerId
                isHost = true
                logger.debug("Generated playerId: ${playerId.value}")
                
                // Create player meta
                val playerMeta = PlayerMeta(
                    playerId = playerId,
                    name = name.trim(),
                    avatarId = avatarId,
                    gender = gender,
                    userId = userProfile?.id
                )
                
                logger.info("Creating game on ${AppConfig.SERVER_URL}")
                
                // Connect to remote server and create game
                val client = realtimeSessionFactory.create()
                gameClient = client
                
                val result = client.createGame(AppConfig.SERVER_URL, playerMeta, superMunchkin, timerSeconds)
                
                if (result.isFailure) {
                    val error = result.exceptionOrNull()?.message ?: appString(R.string.error_unknown)
                    logger.error("Create failed: $error")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error
                        )
                    }
                    return@launch
                }
                
                val gameState = result.getOrNull() ?: return@launch
                myPlayerId = playerId
                
                logger.info("Game created with joinCode: ${gameState.joinCode}")
                
                logger.debug("Game created with joinCode: ${gameState.joinCode}")
                
                // Save game state
                savedGameStore.saveGame(gameState, playerId, true)
                
                // Update state and go to lobby
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        gameState = gameState,
                        myPlayerId = playerId,
                        isHost = true,
                        connectionInfo = ConnectionInfo(
                            wsUrl = AppConfig.SERVER_URL,
                            joinCode = gameState.joinCode,
                            localIp = AppConfig.SERVER_HOST,
                            port = 8765
                        )
                    )
                }
                _events.emit(GameUiEvent.Navigate(GameDestination.LOBBY))
                logger.debug("State updated, navigating to LOBBY")
                
                // Observe game state changes from client (since we are using server)
                observeClientState()
                
            } catch (e: Exception) {
                logger.error("createGame exception", e)
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = appString(
                            R.string.error_prefix_format,
                            e.message ?: appString(R.string.error_unknown)
                        )
                    )
                }
            }
        }
    }
    
    /**
     * Start the game (host only).
     */
    fun startGame() {
        if (!isHost) return
        
        sendPlayerEvent { playerId ->
            eventFactory.gameStart(playerId)
        }
    }
    
    /**
     * Swap two players' positions in the lobby (host only).
     */
    fun swapPlayers(player1: PlayerId, player2: PlayerId) {
        if (!isHost) return
        
        viewModelScope.launch {
            val result = gameClient?.sendSwapPlayers(player1, player2)
                ?: Result.failure(IllegalStateException("No active realtime session"))
            result.onFailure { e ->
                _uiState.update {
                    it.copy(
                        error = appString(
                            R.string.error_reorder_format,
                            e.message ?: appString(R.string.error_unknown)
                        )
                    )
                }
            }
        }
    }
    
    // ============== Client Actions ==============
    
    /**
     * Select a player to view details.
     */
    fun selectPlayer(playerId: PlayerId) {
        _uiState.update { 
            it.copy(selectedPlayerId = playerId)
        }
    }
    
    
    /**
     * Join an existing game as client.
     */
    fun joinGame(
        wsUrl: String,
        joinCode: String,
        name: String,
        avatarId: Int,
        gender: Gender,
        userProfile: UserProfile? = null
    ) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }
                
                // Check for existing playerId for this joinCode (for reconnection)
                val existingPlayerIdStr = playerIdentityStore.getPlayerId(joinCode)
                val playerId = if (existingPlayerIdStr != null) {
                    // Reconnecting with same identity
                    PlayerId(existingPlayerIdStr)
                } else {
                    // First time joining - generate new ID and save
                    val newId = playerIdFactory.create()
                    playerIdentityStore.savePlayerId(joinCode, newId.value)
                    newId
                }
                myPlayerId = playerId
                isHost = false
                
                // Create player meta
                val playerMeta = PlayerMeta(
                    playerId = playerId,
                    name = name.trim(),
                    avatarId = avatarId,
                    gender = gender,
                    userId = userProfile?.id
                )
                
                // Connect to server
                val client = realtimeSessionFactory.create()
                val result = client.connect(wsUrl, joinCode, playerMeta)
                
                if (result.isFailure) {
                    val friendlyError = friendlyErrorMapper.map(result.exceptionOrNull())
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            error = friendlyError
                        ) 
                    }
                    return@launch
                }
                
                gameClient = client
                
                // Update state with received game state
                val gameState = result.getOrNull()
                
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        gameState = gameState,
                        myPlayerId = playerId,
                        isHost = false
                    )
                }
                gameState?.let { state ->
                    _events.emit(GameUiEvent.Navigate(destinationFor(state)))
                }
                
                // Save game immediately for reconnection
                gameState?.let { state ->
                    savedGameStore.saveGame(state, playerId, isHost = false)
                }
                
                // Observe client state changes
                observeClientState()
                
            } catch (e: Exception) {
                val friendlyError = friendlyErrorMapper.map(e)
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = friendlyError
                    )
                }
            }
        }
    }
    
    // ============== Discovery Methods ==============
    
    /**
     * Join a discovered game from server.
     */
    fun joinDiscoveredGame(
        game: DiscoveredGame,
        name: String,
        avatarId: Int,
        gender: Gender,
        userProfile: UserProfile? = null
    ) {
        joinGame(AppConfig.SERVER_URL, game.joinCode, name, avatarId, gender, userProfile)
    }

    /**
     * Increment player level.
     */
    fun incrementLevel() {
        sendPlayerEvent { playerId ->
            eventFactory.incrementLevel(playerId)
        }
    }
    
    /**
     * Decrement player level.
     */
    fun decrementLevel() {
        sendPlayerEvent { playerId ->
            eventFactory.decrementLevel(playerId)
        }
    }
    
    /**
     * Increment gear bonus.
     */
    fun incrementGear(amount: Int = 1) {
        sendPlayerEvent { playerId ->
            eventFactory.incrementGear(playerId, amount)
        }
    }
    
    /**
     * Decrement gear bonus.
     */
    fun decrementGear(amount: Int = 1) {
        sendPlayerEvent { playerId ->
            eventFactory.decrementGear(playerId, amount)
        }
    }
    
    /**
     * Set half-breed status.
     */
    fun setHalfBreed(enabled: Boolean) {
        sendPlayerEvent { playerId ->
            eventFactory.setHalfBreed(playerId, enabled)
        }
    }
    
    /**
     * Set super munchkin status.
     */
    fun setSuperMunchkin(enabled: Boolean) {
        sendPlayerEvent { playerId ->
            eventFactory.setSuperMunchkin(playerId, enabled)
        }
    }
    
    /**
     * Add a race to player.
     */
    fun addRace(entryId: EntryId) {
        sendPlayerEvent { playerId ->
            eventFactory.addRace(playerId, entryId)
        }
    }
    
    /**
     * Remove a race from player.
     */
    fun removeRace(entryId: EntryId) {
        sendPlayerEvent { playerId ->
            eventFactory.removeRace(playerId, entryId)
        }
    }
    
    /**
     * Add a class to player.
     */
    fun addClass(entryId: EntryId) {
        sendPlayerEvent { playerId ->
            eventFactory.addClass(playerId, entryId)
        }
    }
    
    /**
     * Remove a class from player.
     */
    fun removeClass(entryId: EntryId) {
        sendPlayerEvent { playerId ->
            eventFactory.removeClass(playerId, entryId)
        }
    }
    
    /**
     * Add a new race to the catalog.
     */
    fun addRaceToCatalog(displayName: String) {
        sendPlayerEvent { playerId ->
            eventFactory.addRaceToCatalog(playerId, displayName)
        }
    }
    
    /**
     * Add a new class to the catalog.
     */
    fun addClassToCatalog(displayName: String) {
        sendPlayerEvent { playerId ->
            eventFactory.addClassToCatalog(playerId, displayName)
        }
    }
    
    // ============== Combat Actions ==============
    
    fun toggleGender() {
        val playerId = myPlayerId ?: return
        val currentPlayer = _uiState.value.gameState?.players?.get(playerId) ?: return
        
        val newGender = when (currentPlayer.gender) {
            Gender.M -> Gender.F
            Gender.F -> Gender.NA
            Gender.NA -> Gender.M
        }
        
        sendPlayerEvent { pid ->
            eventFactory.setGender(pid, newGender)
        }
    }

    fun setCharacterClass(newClass: CharacterClass) {
        sendPlayerEvent { playerId ->
            eventFactory.setCharacterClass(playerId, newClass)
        }
    }

    fun setCharacterRace(newRace: CharacterRace) {
        sendPlayerEvent { playerId ->
            eventFactory.setCharacterRace(playerId, newRace)
        }
    }

    fun addHelper(helperId: PlayerId) {
        if (helperId == myPlayerId) return
        sendPlayerEvent { playerId ->
            eventFactory.addHelper(playerId, helperId)
        }
    }

    fun removeHelper() {
        sendPlayerEvent { playerId ->
            eventFactory.removeHelper(playerId)
        }
    }

    /**
     * Modify combat bonus/malus for heroes or monsters.
     */
    fun modifyCombatModifier(target: BonusTarget, delta: Int) {
        val currentState = _uiState.value.gameState ?: return
        val currentCombat = currentState.combat ?: return
        
        // Calculate the absolute new value locally
        val currentValue = when (target) {
            BonusTarget.HEROES -> currentCombat.heroModifier
            BonusTarget.MONSTER -> currentCombat.monsterModifier
        }
        val newValue = (currentValue + delta).coerceIn(-20, 20)

        sendPlayerEvent { playerId ->
            eventFactory.setCombatModifier(playerId, target, newValue)
        }
    }

    /**
     * Start combat with current player as main.
     */
    fun startCombat() {
        val playerId = myPlayerId ?: return
        sendPlayerEvent { pid ->
            eventFactory.startCombat(pid, playerId)
        }
    }
    
    /**
     * Add a monster to combat.
     */
    fun addMonster(name: String, level: Int, modifier: Int, isUndead: Boolean) {
        sendPlayerEvent { playerId ->
            eventFactory.addMonster(playerId, name, level, modifier, isUndead)
        }
    }
    
    fun endCombat() {
        val currentGameState = _uiState.value.gameState ?: return
        val currentCombat = currentGameState.combat ?: return
        
        // Calculate result locally to get rewards
        val result = CombatCalculator.calculateResult(currentCombat, currentGameState)
        
        sendPlayerEvent { playerId ->
            eventFactory.endCombat(
                actorId = playerId,
                outcome = result.outcome,
                levelsGained = result.totalLevels,
                treasuresGained = result.totalTreasures,
                helperLevelsGained = result.helperLevelsGained
            )
        }
    }
    


    fun rollDiceForStart() {
        // Roll 1-6
        val result = (1..6).random()
        sendPlayerEvent { playerId ->
            eventFactory.roll(playerId, result)
        }
    }
    
    /**
     * Resolve a run-away attempt after the dice has been rolled.
     * Success → end combat with ESCAPE (no rewards, no penalty).
     * Failure → main player loses 1 level; combat continues.
     */
    fun resolveRunAway(success: Boolean) {
        val currentGameState = _uiState.value.gameState ?: return
        val currentCombat = currentGameState.combat ?: return

        if (success) {
            sendPlayerEvent { playerId ->
                eventFactory.endCombat(playerId, CombatOutcome.ESCAPE)
            }
        } else {
            // Apply 1-level penalty to the main combat player
            sendPlayerEvent { playerId ->
                eventFactory.decrementLevel(
                    actorId = playerId,
                    targetPlayerId = currentCombat.mainPlayerId,
                    amount = 1
                )
            }
        }
    }

    fun rollForCombat(
        purpose: DiceRollPurpose = DiceRollPurpose.RUN_AWAY,
        manualResult: Int? = null,
        success: Boolean = false
    ) {
        // Roll 1-6 if manualResult is null (auto-roll)
        val result = manualResult ?: (1..6).random()
        
        sendPlayerEvent { playerId ->
            eventFactory.roll(
                actorId = playerId,
                result = result,
                purpose = purpose,
                success = success
            )
        }
    }

    // ============== Cleanup ==============
    
    /**
     * Leave current game.
     */
    /**
     * Leave current game.
     */
    /**
     * Kick a disconnected player from the game (Host only).
     */
    fun kickPlayer(targetPlayerId: PlayerId) {
        if (!isHost) return
        viewModelScope.launch {
            gameClient?.kickPlayer(targetPlayerId)
        }
    }

    /**
     * Delete the game (Host only).
     */
    fun deleteGame() {
        if (!isHost) return
        
        viewModelScope.launch {
             try {
                 gameClient?.sendDeleteGame()
                 kotlinx.coroutines.delay(200)
             } catch (e: Exception) {
                 logger.error("Failed to delete: ${e.message}")
             }
             leaveGame()
        }
    }

    /**
     * Leave current game.
     */
    fun leaveGame() {
        viewModelScope.launch {
            // ... (rest of leaveGame)
            if (isHost) {
                try {
                    val playerId = myPlayerId
                    if (playerId != null) {
                        gameClient?.sendEvent(
                             eventFactory.gameEnd(playerId, winnerId = null)
                         )
                         // Give it a moment to send
                         kotlinx.coroutines.delay(500)
                    }
                } catch (e: Exception) {
                    // Ignore error on leave
                }
            }
            
            gameClient?.disconnect()
            gameClient = null
            
            _uiState.update { GameUiState() }
            _events.emit(GameUiEvent.Navigate(GameDestination.HOME))
        }
    }
    

    


    override fun onCleared() {
        super.onCleared()
        val client = gameClient
        if (client != null) {
            kotlinx.coroutines.runBlocking {
                client.disconnect()
            }
        }
    }
    
    // ============== Private Helpers ==============
    
    private fun sendPlayerEvent(eventBuilder: (PlayerId) -> GameEvent) {
        viewModelScope.launch {
            val playerId = myPlayerId ?: return@launch
            val event = eventBuilder(playerId)
            
            val client = gameClient
            if (client != null && client.isConnected()) {
                // Network mode: send to server (the server will broadcast)
                val result = client.sendEvent(event)
                if (result.isFailure) {
                    _events.emit(GameUiEvent.ShowError(appString(R.string.connection_failed)))
                }
            }
        }
    }
    
    private var hasRecordedGame = false

    private fun observeClientState() {
        var previousState: GameState? = null

        viewModelScope.launch {
            gameClient?.gameState?.collect { state ->
                state?.let { s ->
                    val priorState = previousState
                    val transition = stateTransitionAnalyzer.analyze(
                        previous = priorState,
                        current = s,
                        isHost = isHost,
                        hasRecordedGame = hasRecordedGame,
                        currentPendingWinnerId = _uiState.value.pendingWinnerId
                    )
                    transition.logs.forEach { log ->
                        addLogEntry(
                            appString(log.messageResId, *log.args.toTypedArray()),
                            log.type
                        )
                    }
                    previousState = s

                    _uiState.update {
                        it.copy(
                            gameState = s,
                            pendingWinnerId = transition.pendingWinnerId
                        )
                    }

                    if (transition.markGameRecorded) {
                        hasRecordedGame = true
                    }

                    transition.navigation?.let { destination ->
                        _events.emit(GameUiEvent.Navigate(destination))
                    }
                }
            }
        }
        
        viewModelScope.launch {
            var prevConnState: ConnectionState? = null
            gameClient?.connectionState?.collect { connState ->
                // Emit success toast when we recover from a reconnect
                if (prevConnState == ConnectionState.RECONNECTING && connState == ConnectionState.CONNECTED) {
                    _events.emit(GameUiEvent.Reconnected)
                }
                prevConnState = connState
                _uiState.update {
                    it.copy(
                        connectionState = connState,
                        isReconnectFailed = connState == ConnectionState.FAILED_PERMANENTLY
                    )
                }
            }
        }

        viewModelScope.launch {
            gameClient?.reconnectAttempt?.collect { attempt ->
                _uiState.update { it.copy(reconnectAttempt = attempt) }
            }
        }
        
        viewModelScope.launch {
            gameClient?.errors?.collect { error ->
                _events.emit(GameUiEvent.ShowError(error))
            }
        }

        viewModelScope.launch {
            gameClient?.gameDeleted?.collect {
                _events.emit(GameUiEvent.ShowMessage(appString(R.string.game_deleted_by_host)))
                savedGameStore.deleteAllSavedGames()
                _savedGame.value = null
                _uiState.update { GameUiState() }
                _events.emit(GameUiEvent.Navigate(GameDestination.HOME))
            }
        }
    }

    fun endTurn() {
        sendPlayerEvent { playerId ->
            eventFactory.endTurn(playerId)
        }
    }

    fun confirmWin(winnerId: PlayerId) {
        if (!isHost) return
        recordGameOver(_uiState.value.gameState?.gameId?.value ?: return, winnerId.value)
        _uiState.update { it.copy(pendingWinnerId = null) }
    }

    fun dismissWinConfirmation() {
        _uiState.update { it.copy(pendingWinnerId = null) }
    }

    private fun recordGameOver(gameId: String, winnerId: String) {
        if (hasRecordedGame) return
        hasRecordedGame = true
        
        viewModelScope.launch {
            val result = gameClient?.sendGameOver(gameId, winnerId)
                ?: Result.failure(IllegalStateException("No active realtime session"))
            result
                .onSuccess { logger.info("Game over recorded") }
                .onFailure { e ->
                    hasRecordedGame = false
                    _uiState.update { it.copy(pendingWinnerId = PlayerId(winnerId)) }
                    _events.emit(GameUiEvent.ShowError(friendlyErrorMapper.map(e)))
                    logger.error("Failed to record game over: ${e.message}")
                }
        }
    }

    // ============== Game Log ==============

    private fun addLogEntry(message: String, type: LogType = LogType.INFO) {
        val entry = GameLogEntry(message = message, type = type)
        val currentList = _gameLog.value
        _gameLog.value = (currentList + entry).takeLast(50)
    }

    private fun appString(@StringRes resId: Int, vararg args: Any): String {
        return textProvider.get(resId, *args)
    }

    private fun destinationFor(state: GameState): GameDestination {
        return when {
            state.phase == GamePhase.LOBBY -> GameDestination.LOBBY
            state.combat != null -> GameDestination.COMBAT
            else -> GameDestination.BOARD
        }
    }
}



// ============== UI State ==============

data class GameUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val gameState: GameState? = null,
    val myPlayerId: PlayerId? = null,
    val isHost: Boolean = false,
    val connectionInfo: ConnectionInfo? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val reconnectAttempt: Int = 0,          // 0 = not reconnecting; >0 = current attempt number
    val isReconnectFailed: Boolean = false, // true when FAILED_PERMANENTLY

    val pendingWinnerId: PlayerId? = null, // For host to confirm win
    val selectedPlayerId: PlayerId? = null // For viewing player details
) {
    val myPlayer: PlayerState?
        get() = myPlayerId?.let { gameState?.players?.get(it) }
        
    val selectedPlayer: PlayerState?
        get() = (selectedPlayerId ?: myPlayerId)?.let { gameState?.players?.get(it) }
}

data class ConnectionInfo(
    val wsUrl: String,
    val joinCode: String,
    val localIp: String,
    val port: Int
)

enum class GameDestination {
    HOME,
    LOBBY,
    BOARD,
    COMBAT
}

sealed class GameUiEvent {
    data class ShowError(val message: String) : GameUiEvent()
    data class ShowSuccess(val message: String) : GameUiEvent()
    data class ShowMessage(val message: String) : GameUiEvent()
    data class Navigate(val destination: GameDestination) : GameUiEvent()
    data object PlaySound : GameUiEvent()
    data object Reconnected : GameUiEvent()  // Fired when RECONNECTING → CONNECTED transition
}

private data class GameViewModelDependencies(
    val savedGameStore: SavedGameStore,
    val playerIdentityStore: PlayerIdentityStore,
    val realtimeSessionFactory: RealtimeSessionFactory,
    val stateTransitionAnalyzer: GameStateTransitionAnalyzer,
    val eventFactory: GameEventFactory,
    val playerIdFactory: PlayerIdFactory,
    val logger: GameLogger,
    val textProvider: GameTextProvider,
    val friendlyErrorMapper: FriendlyErrorMapper
)

private fun createDefaultGameViewModelDependencies(): GameViewModelDependencies {
    val context = MunchkinApp.context
    val sessionManager = SessionManager(context)
    val textProvider = AndroidGameTextProvider
    return GameViewModelDependencies(
        savedGameStore = GameRepository(context),
        playerIdentityStore = sessionManager,
        realtimeSessionFactory = DefaultRealtimeSessionFactory,
        stateTransitionAnalyzer = GameStateTransitionAnalyzer(),
        eventFactory = GameEventFactory(),
        playerIdFactory = UuidPlayerIdFactory,
        logger = AndroidGameLogger,
        textProvider = textProvider,
        friendlyErrorMapper = FriendlyErrorMapper(textProvider)
    )
}

interface GameLogger {
    fun debug(message: String)
    fun info(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

object NoOpGameLogger : GameLogger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun error(message: String, throwable: Throwable?) = Unit
}

private object AndroidGameLogger : GameLogger {
    override fun debug(message: String) {
        android.util.Log.d("GameViewModel", message)
    }

    override fun info(message: String) {
        DLog.i("GameVM", message)
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            android.util.Log.e("GameViewModel", message, throwable)
        } else {
            DLog.e("GameVM", message)
        }
    }
}
