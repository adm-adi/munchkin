package com.munchkin.app.viewmodel

import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.ui.components.DebugLogManager as DLog
import com.munchkin.app.MunchkinApp
import com.munchkin.app.core.*
import com.munchkin.app.data.GameRepository
import com.munchkin.app.data.SavedGame
import com.munchkin.app.network.*
import com.munchkin.app.update.UpdateChecker
import com.munchkin.app.update.UpdateInfo
import com.munchkin.app.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Main ViewModel managing game state and network operations.
 * Handles both host and client roles.
 */
class GameViewModel : ViewModel() {
    
    companion object {
        // Hetzner VPS server
        private const val SERVER_URL = "ws://23.88.48.58:8765"
    }
    
    // ============== State ==============
    
    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()
    
    private val _events = MutableSharedFlow<GameUiEvent>()
    val events: SharedFlow<GameUiEvent> = _events.asSharedFlow()
    
    private val _discoveredGames = MutableStateFlow<List<DiscoveredGame>>(emptyList())
    val discoveredGames: StateFlow<List<DiscoveredGame>> = _discoveredGames.asStateFlow()
    
    private val _savedGame = MutableStateFlow<SavedGame?>(null)
    val savedGame: StateFlow<SavedGame?> = _savedGame.asStateFlow()
    
    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()
    
    // ============== Update State ==============
    
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()
    
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()
    
    private var updateChecker: UpdateChecker? = null
    
    // ============== Game Components ==============
    
    private var gameEngine: GameEngine? = null
    private var gameServer: GameServer? = null
    private var gameClient: GameClient? = null
    private var nsdHelper: NsdHelper? = null
    private var gameRepository: GameRepository? = null
    
    private var myPlayerId: PlayerId? = null
    private var isHost: Boolean = false
    
    // ============== Initialization ==============
    
    init {
        initRepository()
        loadSavedGame()
        checkForUpdates()
    }
    
    private fun initRepository() {
        try {
            gameRepository = GameRepository(MunchkinApp.context)
        } catch (e: Exception) {
            android.util.Log.e("GameViewModel", "Failed to init repository", e)
        }
    }
    
    private fun loadSavedGame() {
        viewModelScope.launch {
            gameRepository?.getLatestSavedGame()?.collect { saved ->
                _savedGame.value = saved
            }
        }
    }
    
    /**
     * Resume a saved game.
     */
    fun resumeSavedGame() {
        val saved = _savedGame.value ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    screen = if (saved.gameState.phase == GamePhase.LOBBY) Screen.LOBBY else Screen.BOARD,
                    gameState = saved.gameState,
                    myPlayerId = saved.myPlayerId,
                    isHost = saved.isHost
                )
            }
            
            myPlayerId = saved.myPlayerId
            isHost = saved.isHost
            
            if (saved.isHost) {
                // Recreate engine and server
                val engine = GameEngine()
                engine.loadState(saved.gameState)
                gameEngine = engine
                
                val server = GameServer(engine)
                viewModelScope.launch(Dispatchers.IO) {
                    server.start()
                    gameServer = server
                    
                    // Publish via NSD
                    if (nsdHelper == null) {
                        nsdHelper = NsdHelper(MunchkinApp.context)
                    }
                    val localIp = getLocalIpAddress()
                    nsdHelper?.publishGame(
                        hostName = saved.gameState.players[saved.myPlayerId]?.name ?: "Host",
                        joinCode = saved.gameState.joinCode,
                        port = 8765
                    )
                }
                observeGameState()
            }
        }
    }
    
    /**
     * Delete saved game.
     */
    fun deleteSavedGame() {
        viewModelScope.launch {
            gameRepository?.deleteAllSavedGames()
            _savedGame.value = null
        }
    }
    
    // ============== Update Methods ==============
    
    /**
     * Check GitHub for available updates.
     */
    private fun checkForUpdates() {
        if (updateChecker == null) {
            updateChecker = UpdateChecker(MunchkinApp.context)
        }
        viewModelScope.launch {
            when (val result = updateChecker?.checkForUpdate()) {
                is UpdateResult.UpdateAvailable -> {
                    _updateInfo.value = result.info
                }
                is UpdateResult.NoUpdate -> {
                    _updateInfo.value = null
                }
                is UpdateResult.Error -> {
                    android.util.Log.w("GameViewModel", "Update check failed: ${result.message}")
                }
                null -> {}
            }
        }
    }
    
    /**
     * Dismiss update dialog.
     */
    fun dismissUpdate() {
        _updateInfo.value = null
    }
    
    /**
     * Download and install update.
     */
    fun downloadUpdate() {
        val info = _updateInfo.value ?: return
        _isDownloading.value = true
        
        updateChecker?.downloadAndInstall(
            updateInfo = info,
            onProgress = { /* Could update progress here */ },
            onComplete = {
                _isDownloading.value = false
            }
        )
    }
    
    /**
     * Create a new game as host.
     */
    fun createGame(name: String, avatarId: Int, gender: Gender) {
        android.util.Log.d("GameViewModel", "createGame called: name=$name, avatarId=$avatarId, gender=$gender")
        viewModelScope.launch {
            try {
                android.util.Log.d("GameViewModel", "Setting loading state...")
                _uiState.update { it.copy(isLoading = true, error = null) }
                
                // Generate player ID
                val playerId = PlayerId(UUID.randomUUID().toString())
                myPlayerId = playerId
                isHost = true
                android.util.Log.d("GameViewModel", "Generated playerId: ${playerId.value}")
                
                // Create player meta
                val playerMeta = PlayerMeta(
                    playerId = playerId,
                    name = name.trim(),
                    avatarId = avatarId,
                    gender = gender
                )
                
                DLog.i("GameVM", "🎮 Creating game on Hetzner...")
                DLog.i("GameVM", "📡 Server: $SERVER_URL")
                
                // Connect to remote server and create game
                val client = GameClient()
                gameClient = client
                
                val result = client.createGame(SERVER_URL, playerMeta)
                
                if (result.isFailure) {
                    val error = result.exceptionOrNull()?.message ?: "Error desconocido"
                    DLog.e("GameVM", "❌ Create failed: $error")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error
                        )
                    }
                    return@launch
                }
                
                val gameState = result.getOrNull()!!
                myPlayerId = playerId
                
                DLog.i("GameVM", "✅ Game created!")
                DLog.i("GameVM", "🔑 Code: ${gameState.joinCode}")
                
                android.util.Log.d("GameViewModel", "Game created with joinCode: ${gameState.joinCode}")
                
                // Save game state
                gameRepository?.saveGame(gameState, playerId, true)
                
                // Update state and go to lobby
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        screen = Screen.LOBBY,
                        gameState = gameState,
                        myPlayerId = playerId,
                        isHost = true,
                        connectionInfo = ConnectionInfo(
                            wsUrl = SERVER_URL,
                            joinCode = gameState.joinCode,
                            localIp = "23.88.48.58",
                            port = 8765
                        )
                    )
                }
                android.util.Log.d("GameViewModel", "State updated, navigating to LOBBY")
                
                // Observe game state changes
                observeGameState()
                
            } catch (e: Exception) {
                android.util.Log.e("GameViewModel", "createGame exception", e)
                _uiState.update { 
                    it.copy(isLoading = false, error = "Error: ${e.message}") 
                }
            }
        }
    }
    
    /**
     * Start the game (host only).
     */
    fun startGame() {
        viewModelScope.launch {
            if (!isHost) return@launch
            
            val engine = gameEngine ?: return@launch
            val state = engine.gameState.value ?: return@launch
            val pid = myPlayerId ?: return@launch
            
            val event = GameStart(
                eventId = UUID.randomUUID().toString(),
                actorId = pid,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = null
            )
            
            val result = engine.processEvent(event)
            if (result is ValidationResult.Error) {
                _events.emit(GameUiEvent.ShowError(result.message))
            }
        }
    }
    
    // ============== Client Actions ==============
    
    /**
     * Join an existing game as client.
     */
    fun joinGame(
        wsUrl: String,
        joinCode: String,
        name: String,
        avatarId: Int,
        gender: Gender
    ) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }
                
                // Generate player ID
                val playerId = PlayerId(UUID.randomUUID().toString())
                myPlayerId = playerId
                isHost = false
                
                // Create player meta
                val playerMeta = PlayerMeta(
                    playerId = playerId,
                    name = name.trim(),
                    avatarId = avatarId,
                    gender = gender
                )
                
                // Connect to server
                val client = GameClient()
                val result = client.connect(wsUrl, joinCode, playerMeta)
                
                if (result.isFailure) {
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            error = "Error al conectar: ${result.exceptionOrNull()?.message}"
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
                        screen = if (gameState?.phase == GamePhase.LOBBY) Screen.LOBBY else Screen.BOARD,
                        gameState = gameState,
                        myPlayerId = playerId,
                        isHost = false
                    )
                }
                
                // Observe client state changes
                observeClientState()
                
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(isLoading = false, error = "Error: ${e.message}") 
                }
            }
        }
    }
    
    // ============== Discovery Methods ==============
    
    /**
     * Start discovering games on the local network.
     */
    fun startDiscovery() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true, discoveredGames = emptyList()) }
            
            try {
                if (nsdHelper == null) {
                    nsdHelper = NsdHelper(MunchkinApp.context)
                }
                
                nsdHelper?.startDiscovery()
                
                // Collect discovered games from NsdHelper
                viewModelScope.launch {
                    nsdHelper?.discoveredGames?.collect { games ->
                        val discovered = games.map { game ->
                            com.munchkin.app.ui.screens.DiscoveredGame(
                                hostName = game.hostName,
                                joinCode = game.joinCode,
                                wsUrl = game.wsUrl,
                                port = game.port
                            )
                        }
                        _uiState.update { 
                            it.copy(discoveredGames = discovered)
                        }
                    }
                }
                
                // Wait a bit then update discovering status
                kotlinx.coroutines.delay(3000)
                _uiState.update { it.copy(isDiscovering = false) }
                
            } catch (e: Exception) {
                android.util.Log.e("GameViewModel", "Discovery failed", e)
                _uiState.update { it.copy(isDiscovering = false) }
            }
        }
    }
    
    /**
     * Join a discovered game from NSD.
     */
    fun joinDiscoveredGame(
        game: com.munchkin.app.ui.screens.DiscoveredGame,
        name: String,
        avatarId: Int,
        gender: Gender
    ) {
        joinGame(game.wsUrl, game.joinCode, name, avatarId, gender)
    }
    
    /**
     * Stop game discovery.
     */
    fun stopDiscovery() {
        nsdHelper?.stopDiscovery()
        _uiState.update { it.copy(isDiscovering = false) }
    }
    
    // ============== Player Actions ==============
    
    /**
     * Increment player level.
     */
    fun incrementLevel() {
        sendPlayerEvent { playerId ->
            IncLevel(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId
            )
        }
    }
    
    /**
     * Decrement player level.
     */
    fun decrementLevel() {
        sendPlayerEvent { playerId ->
            DecLevel(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId
            )
        }
    }
    
    /**
     * Increment gear bonus.
     */
    fun incrementGear() {
        sendPlayerEvent { playerId ->
            IncGear(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId
            )
        }
    }
    
    /**
     * Decrement gear bonus.
     */
    fun decrementGear() {
        sendPlayerEvent { playerId ->
            DecGear(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId
            )
        }
    }
    
    /**
     * Set half-breed status.
     */
    fun setHalfBreed(enabled: Boolean) {
        sendPlayerEvent { playerId ->
            SetHalfBreed(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId,
                enabled = enabled
            )
        }
    }
    
    /**
     * Set super munchkin status.
     */
    fun setSuperMunchkin(enabled: Boolean) {
        sendPlayerEvent { playerId ->
            SetSuperMunchkin(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId,
                enabled = enabled
            )
        }
    }
    
    /**
     * Add a race to player.
     */
    fun addRace(entryId: EntryId) {
        sendPlayerEvent { playerId ->
            AddRace(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId,
                entryId = entryId
            )
        }
    }
    
    /**
     * Remove a race from player.
     */
    fun removeRace(entryId: EntryId) {
        sendPlayerEvent { playerId ->
            RemoveRace(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId,
                entryId = entryId
            )
        }
    }
    
    /**
     * Add a class to player.
     */
    fun addClass(entryId: EntryId) {
        sendPlayerEvent { playerId ->
            AddClass(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId,
                entryId = entryId
            )
        }
    }
    
    /**
     * Remove a class from player.
     */
    fun removeClass(entryId: EntryId) {
        sendPlayerEvent { playerId ->
            RemoveClass(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId,
                entryId = entryId
            )
        }
    }
    
    /**
     * Add a new race to the catalog.
     */
    fun addRaceToCatalog(displayName: String) {
        sendPlayerEvent { playerId ->
            CatalogAddRace(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                displayName = displayName
            )
        }
    }
    
    /**
     * Add a new class to the catalog.
     */
    fun addClassToCatalog(displayName: String) {
        sendPlayerEvent { playerId ->
            CatalogAddClass(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                displayName = displayName
            )
        }
    }
    
    // ============== Combat Actions ==============
    
    /**
     * Start combat with current player as main.
     */
    fun startCombat() {
        val playerId = myPlayerId ?: return
        sendPlayerEvent { pid ->
            CombatStart(
                eventId = UUID.randomUUID().toString(),
                actorId = pid,
                timestamp = System.currentTimeMillis(),
                mainPlayerId = playerId
            )
        }
    }
    
    /**
     * Add a monster to combat.
     */
    fun addMonster(name: String, level: Int, modifier: Int = 0) {
        sendPlayerEvent { playerId ->
            CombatAddMonster(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                monster = MonsterInstance(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    baseLevel = level,
                    flatModifier = modifier
                )
            )
        }
    }
    
    /**
     * End combat.
     */
    fun endCombat(outcome: CombatOutcome, levelsGained: Int = 0) {
        sendPlayerEvent { playerId ->
            CombatEnd(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                outcome = outcome,
                levelsGained = levelsGained
            )
        }
    }
    
    // ============== Navigation ==============
    
    fun navigateTo(screen: Screen) {
        _uiState.update { it.copy(screen = screen) }
    }
    
    fun goBack() {
        val currentScreen = _uiState.value.screen
        val newScreen = when (currentScreen) {
            Screen.PLAYER_DETAIL, Screen.COMBAT, Screen.CATALOG -> Screen.BOARD
            Screen.SETTINGS -> {
                if (_uiState.value.gameState != null) Screen.BOARD else Screen.HOME
            }
            else -> Screen.HOME
        }
        _uiState.update { it.copy(screen = newScreen) }
    }
    
    // ============== Cleanup ==============
    
    /**
     * Leave current game.
     */
    fun leaveGame() {
        viewModelScope.launch {
            // Stop NSD publishing/discovery
            nsdHelper?.cleanup()
            nsdHelper = null
            
            gameServer?.stop()
            gameServer = null
            gameClient?.disconnect()
            gameClient = null
            gameEngine = null
            
            _uiState.update {
                GameUiState(screen = Screen.HOME)
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        nsdHelper?.cleanup()
        viewModelScope.launch {
            gameServer?.stop()
            gameClient?.disconnect()
        }
    }
    
    // ============== Private Helpers ==============
    
    private fun sendPlayerEvent(eventBuilder: (PlayerId) -> GameEvent) {
        viewModelScope.launch {
            val playerId = myPlayerId ?: return@launch
            val event = eventBuilder(playerId)
            
            if (isHost) {
                // Process locally
                val engine = gameEngine ?: return@launch
                val result = engine.processEvent(event)
                if (result is ValidationResult.Error) {
                    _events.emit(GameUiEvent.ShowError(result.message))
                }
            } else {
                // Send to server
                val client = gameClient ?: return@launch
                val result = client.sendEvent(event)
                if (result.isFailure) {
                    _events.emit(GameUiEvent.ShowError("Error de conexión"))
                }
            }
        }
    }
    
    private fun observeGameState() {
        viewModelScope.launch {
            gameEngine?.gameState?.collect { state ->
                state?.let { s ->
                    _uiState.update { it.copy(gameState = s) }
                    
                    // Check for phase change
                    if (s.phase == GamePhase.IN_GAME && _uiState.value.screen == Screen.LOBBY) {
                        _uiState.update { it.copy(screen = Screen.BOARD) }
                    }
                    
                    // Auto-save state
                    myPlayerId?.let { pid ->
                        gameRepository?.saveGame(s, pid, isHost)
                    }
                }
            }
        }
    }
    
    private fun observeClientState() {
        viewModelScope.launch {
            gameClient?.gameState?.collect { state ->
                state?.let { s ->
                    _uiState.update { it.copy(gameState = s) }
                    
                    // Check for phase change
                    if (s.phase == GamePhase.IN_GAME && _uiState.value.screen == Screen.LOBBY) {
                        _uiState.update { it.copy(screen = Screen.BOARD) }
                    }
                }
            }
        }
        
        viewModelScope.launch {
            gameClient?.connectionState?.collect { connState ->
                _uiState.update { it.copy(connectionState = connState) }
            }
        }
        
        viewModelScope.launch {
            gameClient?.errors?.collect { error ->
                _events.emit(GameUiEvent.ShowError(error))
            }
        }
    }
    
    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = MunchkinApp.context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
        
        return "192.168.1.1"  // Fallback
    }
}

// ============== UI State ==============

data class GameUiState(
    val screen: Screen = Screen.HOME,
    val isLoading: Boolean = false,
    val error: String? = null,
    val gameState: GameState? = null,
    val myPlayerId: PlayerId? = null,
    val isHost: Boolean = false,
    val connectionInfo: ConnectionInfo? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val discoveredGames: List<com.munchkin.app.ui.screens.DiscoveredGame> = emptyList(),
    val isDiscovering: Boolean = false
) {
    val myPlayer: PlayerState?
        get() = myPlayerId?.let { gameState?.players?.get(it) }
}

data class ConnectionInfo(
    val wsUrl: String,
    val joinCode: String,
    val localIp: String,
    val port: Int
)

enum class Screen {
    HOME,
    CREATE_GAME,
    JOIN_GAME,
    LOBBY,
    BOARD,
    PLAYER_DETAIL,
    COMBAT,
    CATALOG,
    SETTINGS
}

sealed class GameUiEvent {
    data class ShowError(val message: String) : GameUiEvent()
    data class ShowSuccess(val message: String) : GameUiEvent()
    data object PlaySound : GameUiEvent()
}
