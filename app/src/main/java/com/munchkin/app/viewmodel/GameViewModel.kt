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
import com.munchkin.app.data.SessionManager
import com.munchkin.app.network.*
import com.munchkin.app.update.UpdateChecker
import com.munchkin.app.update.UpdateInfo
import com.munchkin.app.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import java.util.UUID
import com.munchkin.app.network.DiscoveredGame

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

    private val _gameLog = MutableStateFlow<List<GameLogEntry>>(emptyList())
    val gameLog: StateFlow<List<GameLogEntry>> = _gameLog.asStateFlow()


    
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
    private var sessionManager: SessionManager? = null
    
    private var myPlayerId: PlayerId? = null
    private var isHost: Boolean = false
    
    // ============== Initialization ==============
    
    init {
        initData()
        checkForUpdates()
    }
    
    private fun initData() {
        try {
            val context = MunchkinApp.context
            gameRepository = GameRepository(context)
            sessionManager = SessionManager(context)
            
            // Load Saved Game
            viewModelScope.launch {
                gameRepository?.getLatestSavedGame()?.collect { saved ->
                    _savedGame.value = saved
                }
            }
            
            // Restore Session & Auto-Login
            val savedProfile = sessionManager?.getSession()
            val savedToken = sessionManager?.getAuthToken()
            
            if (savedProfile != null) {
                _uiState.update { it.copy(userProfile = savedProfile) }
                
                // If we have a token, try to validate it / auto-login
                if (savedToken != null) {
                    performAutoLogin(savedToken)
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.e("GameViewModel", "Failed to init data", e)
        }
    }

    private fun performAutoLogin(token: String) {
        viewModelScope.launch {
            try {
                // We use a temp client to validate the token
                val client = GameClient()
                val result = client.loginWithToken(SERVER_URL, token)
                
                if (result.isSuccess) {
                    val authData = result.getOrNull()
                    if (authData != null) {
                        // Update profile with latest from server
                        _uiState.update { it.copy(userProfile = authData.user) }
                        sessionManager?.saveSession(authData.user)
                        // If server rotated token, save it (authData.token)
                        authData.token?.let { sessionManager?.saveAuthToken(it) }
                        
                        android.util.Log.i("GameViewModel", "✅ Auto-login success for ${authData.user.username}")
                    }
                } else {
                    android.util.Log.w("GameViewModel", "⚠️ Auto-login failed, clearing session")
                    // Token expired or invalid
                    // sessionManager?.clearSession() // Optional: force logout vs keeping stale profile
                    // For now, let's keep profile but maybe show a "Session Expired" if they try to do something
                }
            } catch (e: Exception) {
                android.util.Log.e("GameViewModel", "Auto-login error", e)
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
                    name = player?.name ?: "Player",
                    avatarId = player?.avatarId ?: 0,
                    gender = player?.gender ?: Gender.M,
                    userId = _uiState.value.userProfile?.id
                )
                
                val client = GameClient()
                val result = client.connect(SERVER_URL, saved.gameState.joinCode, playerMeta)
                
                if (result.isFailure) {
                    val friendlyError = getFriendlyErrorMessage(result.exceptionOrNull())
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
                
                // Initialize local engine for state tracking
                val engine = GameEngine()
                gameState?.let { engine.loadState(it) }
                gameEngine = engine
                
                _uiState.update {
                    it.copy(
                        isLoading = false,

                        screen = if ((gameState?.phase ?: saved.gameState.phase) == GamePhase.LOBBY) {
                            Screen.LOBBY
                        } else if (gameState?.combat != null) {
                            Screen.COMBAT
                        } else {
                            Screen.BOARD
                        },
                        gameState = gameState ?: saved.gameState,
                        myPlayerId = saved.myPlayerId,
                        isHost = saved.isHost
                    )
                }
                
                // Observe game state changes from client
                observeClientState()
                
            } catch (e: Exception) {
                val friendlyError = getFriendlyErrorMessage(e)
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
    fun checkReconnection() {
        val client = gameClient
        val saved = _savedGame.value
        
        if (saved != null && (client == null || !client.isConnected())) {
            android.util.Log.d("GameViewModel", "Auto-reconnecting to saved game...")
            resumeSavedGame()
        }
    }
    
    /**
     * Delete saved game.
     */
    fun deleteSavedGame() {
        val saved = _savedGame.value ?: return
        
        viewModelScope.launch {
            // If host, try to tell server to delete
            if (saved.isHost) {
                _uiState.update { it.copy(isLoading = true) }
                try {
                    // Temporary connection to delete
                    val client = GameClient()
                    
                    val playerMeta = PlayerMeta(
                        playerId = saved.myPlayerId,
                        name = "Host", // Name doesn't matter for this op validation
                        avatarId = 0,
                        gender = Gender.M,
                        userId = _uiState.value.userProfile?.id
                    )
                    
                    val connectResult = client.connect(SERVER_URL, saved.gameState.joinCode, playerMeta)
                    if (connectResult.isSuccess) {
                        client.sendDeleteGame()
                        // Wait a bit for server to process broadcast
                        kotlinx.coroutines.delay(500)
                        client.disconnect()
                    }
                } catch (e: Exception) {
                    DLog.e("GameVM", "Failed to delete on server: ${e.message}")
                }
            }
            
            // Delete locally
            gameRepository?.deleteAllSavedGames()
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
     * Force check for updates from Settings screen.
     */
    fun forceCheckUpdate() {
        if (updateChecker == null) {
            updateChecker = UpdateChecker(MunchkinApp.context)
        }
        
        _uiState.update { it.copy(isCheckingUpdate = true) }
        
        viewModelScope.launch {
            try {
                when (val result = updateChecker?.checkForUpdate()) {
                    is UpdateResult.UpdateAvailable -> {
                        _updateInfo.value = result.info
                        _events.emit(GameUiEvent.ShowMessage("Nueva versión ${result.info.version} disponible"))
                    }
                    is UpdateResult.NoUpdate -> {
                        _updateInfo.value = null
                        _events.emit(GameUiEvent.ShowMessage("Ya tienes la última versión"))
                    }
                    is UpdateResult.Error -> {
                        _events.emit(GameUiEvent.ShowMessage("Error: ${result.message}"))
                    }
                    null -> {}
                }
            } finally {
                _uiState.update { it.copy(isCheckingUpdate = false) }
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
    fun createGame(name: String, avatarId: Int, gender: Gender, timerSeconds: Int = 0) {
        android.util.Log.d("GameViewModel", "createGame called: name=$name, avatarId=$avatarId, gender=$gender, timer=$timerSeconds")
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
                val user = _uiState.value.userProfile
                val playerMeta = PlayerMeta(
                    playerId = playerId,
                    name = name.trim(),
                    avatarId = avatarId,
                    gender = gender,
                    userId = user?.id
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
                
                // Initialize local engine for state tracking
                val engine = GameEngine()
                engine.loadState(gameState)
                gameEngine = engine
                
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
                
                // Observe game state changes from client (since we are using server)
                observeClientState()
                
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
        if (!isHost) return
        
        sendPlayerEvent { playerId ->
            GameStart(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Swap two players' positions in the lobby (host only).
     */
    fun swapPlayers(player1: PlayerId, player2: PlayerId) {
        if (!isHost) return
        
        viewModelScope.launch {
            try {
                gameClient?.sendSwapPlayers(player1, player2)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Error al reordenar: ${e.message}") }
            }
        }
    }
    
    // ============== Client Actions ==============
    
    /**
     * Select a player to view details.
     */
    fun selectPlayer(playerId: PlayerId) {
        _uiState.update { 
            it.copy(
                selectedPlayerId = playerId, 
                screen = Screen.PLAYER_DETAIL
            ) 
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
        gender: Gender
    ) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }
                
                // Check for existing playerId for this joinCode (for reconnection)
                val existingPlayerIdStr = sessionManager?.getPlayerId(joinCode)
                val playerId = if (existingPlayerIdStr != null) {
                    // Reconnecting with same identity
                    PlayerId(existingPlayerIdStr)
                } else {
                    // First time joining - generate new ID and save
                    val newId = PlayerId(UUID.randomUUID().toString())
                    sessionManager?.savePlayerId(joinCode, newId.value)
                    newId
                }
                myPlayerId = playerId
                isHost = false
                
                // Create player meta
                val user = _uiState.value.userProfile
                val playerMeta = PlayerMeta(
                    playerId = playerId,
                    name = name.trim(),
                    avatarId = avatarId,
                    gender = gender,
                    userId = user?.id
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
                
                // Initialize local engine for state tracking
                val engine = GameEngine()
                gameState?.let { engine.loadState(it) }
                gameEngine = engine
                
                _uiState.update {
                    it.copy(
                        isLoading = false,

                        screen = if (gameState?.phase == GamePhase.LOBBY) {
                            Screen.LOBBY
                        } else if (gameState?.combat != null) {
                            Screen.COMBAT
                        } else {
                             Screen.BOARD
                        },
                        gameState = gameState,
                        myPlayerId = playerId,
                        isHost = false
                    )
                }
                
                // Save game immediately for reconnection
                gameState?.let { state ->
                    gameRepository?.saveGame(state, playerId, isHost = false)
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
     * Start discovering games from the server.
     */
    fun startDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isDiscovering = true, discoveredGames = emptyList()) }
            
            try {
                // Connect to server and request games list
                val client = HttpClient(CIO) {
                    install(WebSockets)
                }
                
                client.webSocket(SERVER_URL) {
                    // Send list games request
                    send("{\"type\": \"LIST_GAMES\"}")
                    
                    // Wait for response
                    val frame = incoming.receive()
                    if (frame is io.ktor.websocket.Frame.Text) {
                        val text = frame.readText()
                        DLog.i("GameViewModel", "Games list response: $text")
                        
                        // Parse response
                        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        val response = json.decodeFromString<GamesListResponse>(text)
                        
                        val discovered = response.games.map { game ->
                            DiscoveredGame(
                                hostName = game.hostName,
                                joinCode = game.joinCode,
                                playerCount = game.playerCount,
                                maxPlayers = game.maxPlayers,
                                wsUrl = SERVER_URL
                            )
                        }
                        
                        _uiState.update { it.copy(discoveredGames = discovered) }
                    }
                }
                
                client.close()
                
            } catch (e: Exception) {
                DLog.e("GameViewModel", "Discovery failed: ${e.message}")
            } finally {
                _uiState.update { it.copy(isDiscovering = false) }
            }
        }
    }
    
    /**
     * Join a discovered game from server.
     */
    fun joinDiscoveredGame(
        game: DiscoveredGame,
        name: String,
        avatarId: Int,
        gender: Gender
    ) {
        joinGame(SERVER_URL, game.joinCode, name, avatarId, gender)
    }
    

    
    fun stopDiscovery() {
        nsdHelper?.stopDiscovery()
        _uiState.update { it.copy(isDiscovering = false) }
    }

    // ============== Auth Methods ==============
    
    fun register(username: String, email: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Use a temporary client for auth if gameClient is not initialized specific for auth
                // Or just use GameClient helper
                val client = GameClient() // Temp instance
                val result = client.register(SERVER_URL, username, email, pass)
                
                if (result.isSuccess) {
                    val authData = result.getOrNull()
                    if (authData != null) {
                        sessionManager?.saveSession(authData.user)
                        authData.token?.let { sessionManager?.saveAuthToken(it) }
                    }
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            userProfile = authData?.user,
                            screen = Screen.HOME // Go back to home after login/reg
                        ) 
                    }
                    _events.emit(GameUiEvent.ShowSuccess("Bienvenido, ${authData?.user?.username}!"))
                } else {
                    _uiState.update { 
                        it.copy(isLoading = false, error = result.exceptionOrNull()?.message)
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
    
    fun login(email: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val client = GameClient()
                val result = client.login(SERVER_URL, email, pass)
                
                if (result.isSuccess) {
                    val authData = result.getOrNull()
                    if (authData != null) {
                        sessionManager?.saveSession(authData.user)
                        authData.token?.let { sessionManager?.saveAuthToken(it) }
                    }
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            userProfile = authData?.user,
                            screen = Screen.HOME
                        ) 
                    }
                    _events.emit(GameUiEvent.ShowSuccess("Hola de nuevo, ${authData?.user?.username}!"))
                } else {
                    _uiState.update { 
                        it.copy(isLoading = false, error = result.exceptionOrNull()?.message)
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
    fun logout() {
        sessionManager?.clearSession()
        _uiState.update { it.copy(userProfile = null) }
    }
    
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
    fun incrementGear(amount: Int = 1) {
        sendPlayerEvent { playerId ->
            IncGear(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId,
                amount = amount
            )
        }
    }
    
    /**
     * Decrement gear bonus.
     */
    fun decrementGear(amount: Int = 1) {
        sendPlayerEvent { playerId ->
            DecGear(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId,
                amount = amount
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
    
    fun toggleGender() {
        val playerId = myPlayerId ?: return
        val currentPlayer = _uiState.value.gameState?.players?.get(playerId) ?: return
        
        val newGender = when (currentPlayer.gender) {
            Gender.M -> Gender.F
            Gender.F -> Gender.NA
            Gender.NA -> Gender.M
        }
        
        sendPlayerEvent { pid ->
            SetGender(
                eventId = UUID.randomUUID().toString(),
                actorId = pid,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = pid,
                gender = newGender
            )
        }
    }

    fun setCharacterClass(newClass: CharacterClass) {
        sendPlayerEvent { playerId ->
            SetClass(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId,
                newClass = newClass
            )
        }
    }

    fun setCharacterRace(newRace: CharacterRace) {
        sendPlayerEvent { playerId ->
            SetRace(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId,
                newRace = newRace
            )
        }
    }

    fun addHelper(helperId: PlayerId) {
        sendPlayerEvent { playerId ->
            CombatAddHelper(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                helperId = helperId
            )
        }
    }

    fun removeHelper() {
        sendPlayerEvent { playerId ->
            CombatRemoveHelper(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis()
            )
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
        val newValue = currentValue + delta
        
        sendPlayerEvent { playerId ->
            CombatSetModifier(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                target = target,
                value = newValue
            )
        }
    }

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

    
    // ============== Catalog Actions ==============

    fun searchMonsters(query: String) {
        viewModelScope.launch {
            if (query.isBlank()) {
                _uiState.update { it.copy(monsterSearchResults = emptyList()) }
                return@launch
            }
            
            try {
                // Use temp client or existing game client
                val client = GameClient()
                val result = client.searchMonsters(SERVER_URL, query)
                
                if (result.isSuccess) {
                    _uiState.update { 
                        it.copy(monsterSearchResults = result.getOrElse { emptyList() }) 
                    }
                }
            } catch (e: Exception) {
                // Ignore errors for search
            }
        }
    }

    fun requestCreateGlobalMonster(name: String, level: Int, modifier: Int, isUndead: Boolean) {
        val user = _uiState.value.userProfile
        val userId = user?.id ?: myPlayerId?.value ?: "anon"
        
        val monster = CatalogMonster(
            name = name,
            level = level,
            modifier = modifier,
            isUndead = isUndead,
            createdBy = user?.username
        )

        viewModelScope.launch {
            try {
                val client = GameClient()
                val result = client.addMonsterToCatalog(SERVER_URL, monster, userId)
                
                if (result.isSuccess) {
                    val created = result.getOrNull()
                    if (created != null) {
                        // Auto-add to combat if in combat?
                        addMonster(created.name, created.level, created.modifier, created.isUndead)
                        _events.emit(GameUiEvent.ShowSuccess("Monstruo creado: ${created.name}"))
                        
                        // Also trigger a search to show it?
                    }
                } else {
                    _events.emit(GameUiEvent.ShowError("Error al guardar monstruo"))
                }
            } catch (e: Exception) {
                _events.emit(GameUiEvent.ShowError("Error: ${e.message}"))
            }
        }
    }

    /**
     * Add a monster to combat.
     */
    fun addMonster(name: String, level: Int, modifier: Int, isUndead: Boolean) {
        sendPlayerEvent { playerId ->
            CombatAddMonster(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                monster = MonsterInstance(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    baseLevel = level,
                    flatModifier = modifier,
                    isUndead = isUndead
                )
            )
        }
    }
    
    fun endCombat() {
        val currentGameState = _uiState.value.gameState ?: return
        val currentCombat = currentGameState.combat ?: return
        
        // Calculate result locally to get rewards
        val result = CombatCalculator.calculateResult(currentCombat, currentGameState)
        
        sendPlayerEvent { playerId ->
            CombatEnd(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                outcome = result.outcome,
                levelsGained = result.totalLevels,
                treasuresGained = result.totalTreasures
            )
        }
    }
    


    fun rollDiceForStart() {
        // Roll 1-6
        val result = (1..6).random()
        sendPlayerEvent { playerId ->
            PlayerRoll(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId, // Self
                result = result
            )
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
            PlayerRoll(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = playerId, // Self
                result = result,
                purpose = purpose,
                success = success
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
    /**
     * Leave current game.
     */
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
                 DLog.e("GameVM", "Failed to delete: ${e.message}")
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
                             GameEnd(
                                 eventId = UUID.randomUUID().toString(),
                                 actorId = playerId,
                                 timestamp = System.currentTimeMillis(),
                                 winnerId = null // Aborted
                             )
                         )
                         // Give it a moment to send
                         kotlinx.coroutines.delay(500)
                    }
                } catch (e: Exception) {
                    // Ignore error on leave
                }
            }
            
            // Stop NSD publishing/discovery
            nsdHelper?.cleanup()
            nsdHelper = null
            
            gameServer?.stop()
            gameServer = null
            gameClient?.disconnect()
            gameClient = null
            gameEngine = null
            
            _uiState.update {
                GameUiState(screen = Screen.HOME, userProfile = it.userProfile)
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
            
            val client = gameClient
            if (client != null && client.isConnected()) {
                // Network mode: send to server (the server will broadcast)
                val result = client.sendEvent(event)
                if (result.isFailure) {
                    _events.emit(GameUiEvent.ShowError("Error de conexión"))
                }
            } else if (isHost) {
                // Local mode (offline/host): process locally
                val engine = gameEngine ?: return@launch
                val result = engine.processEvent(event)
                if (result is ValidationResult.Error) {
                    _events.emit(GameUiEvent.ShowError(result.message))
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
    
    private var hasRecordedGame = false

    private fun observeClientState() {
        var previousState: GameState? = null

        viewModelScope.launch {
            gameClient?.gameState?.collect { state ->
                state?.let { s ->
                    // Log Level Changes
                    previousState?.let { prev ->
                        s.players.forEach { (id, player) ->
                            val prevPlayer = prev.players[id]
                            if (prevPlayer != null && prevPlayer.level != player.level) {
                                val diff = player.level - prevPlayer.level
                                if (diff > 0) {
                                    addLogEntry("${player.name} subió a Nivel ${player.level}", LogType.LEVEL_UP)
                                } else {
                                    addLogEntry("${player.name} bajó a Nivel ${player.level}", LogType.INFO)
                                }
                            }
                        }
                        
                        // Log Combat Result (Combat property cleared)
                        if (prev.combat != null && s.combat == null) {
                            addLogEntry("Combate finalizado", LogType.combat)
                        }
                    }
                    previousState = s

                    _uiState.update { it.copy(gameState = s) }
                    
                    // HOST CHECK: Did someone reach max level?
                    if (isHost && s.phase == GamePhase.IN_GAME && !hasRecordedGame) {
                        val winner = s.players.values.find { it.level >= s.settings.maxLevel }
                        if (winner != null && _uiState.value.pendingWinnerId != winner.playerId) {
                            // Show confirmation dialog
                            _uiState.update { it.copy(pendingWinnerId = winner.playerId) }
                        } else if (winner == null && _uiState.value.pendingWinnerId != null) {
                            // Level went back down? Dismiss
                            _uiState.update { it.copy(pendingWinnerId = null) }
                        }
                    }

                    // Check for Game Over (Server confirmed)
                    if (s.phase == GamePhase.FINISHED && s.winnerId != null) {
                         // Ensure we don't record twice if server sent it back
                         hasRecordedGame = true 
                    }

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
                if (error == "La partida ha sido eliminada por el anfitrión") {
                    _events.emit(GameUiEvent.ShowMessage(error))
                    // Clear local save and state
                    gameRepository?.deleteAllSavedGames()
                    _savedGame.value = null
                    _uiState.update { GameUiState(screen = Screen.HOME, userProfile = it.userProfile) }
                } else {
                    _events.emit(GameUiEvent.ShowError(error))
                }
            }
        }
    }

    fun endTurn() {
        sendPlayerEvent { playerId ->
            EndTurn(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis()
            )
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
            try {
                gameClient?.sendGameOver(SERVER_URL, gameId, winnerId)
                DLog.i("GameVM", "🏆 Game Over recorded!")
            } catch (e: Exception) {
                DLog.e("GameVM", "Failed to record game over: ${e.message}")
            }
        }
    }


    fun loadHistory() {
        val user = _uiState.value.userProfile ?: return
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                val client = GameClient()
                val result = client.getHistory(SERVER_URL, user.id)
                if (result.isSuccess) {
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            gameHistory = result.getOrElse { emptyList() }
                        ) 
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
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
    // ============== Game Log ==============

    fun loadLeaderboard() {
        if (_uiState.value.isLoading) return
        val client = gameClient ?: return
        
        _uiState.update { it.copy(isLoading = true) }
        
        viewModelScope.launch {
            client.getLeaderboard(SERVER_URL)
                .onSuccess { leaderboard ->
                    _uiState.update { it.copy(leaderboard = leaderboard, isLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }
    
    fun updateProfile(username: String?, pass: String?) {
        val currentUser = _uiState.value.userProfile ?: return
        val client = gameClient ?: return
        if (username.isNullOrBlank() && pass.isNullOrBlank()) return
        
        _uiState.update { it.copy(isLoading = true) }
        
        viewModelScope.launch {
            val result = client.updateProfile(SERVER_URL, currentUser.id, username, pass)
            if (result.isSuccess) {
                val updatedUser = result.getOrThrow()
                _uiState.update { it.copy(userProfile = updatedUser, isLoading = false) }
                _events.emit(GameUiEvent.ShowMessage("Perfil actualizado"))
            } else {
                val error = result.exceptionOrNull()?.message ?: "Error desconocido"
                _uiState.update { it.copy(isLoading = false, error = error) }
            }
        }
    }

    private fun addLogEntry(message: String, type: LogType = LogType.INFO) {
        val entry = GameLogEntry(message = message, type = type)
        val currentList = _gameLog.value
        _gameLog.value = (currentList + entry).takeLast(50)
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

    val discoveredGames: List<DiscoveredGame> = emptyList(),
    val isDiscovering: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val userProfile: UserProfile? = null,

    val monsterSearchResults: List<CatalogMonster> = emptyList(),
    val gameHistory: List<GameHistoryItem> = emptyList(),
    val leaderboard: List<LeaderboardEntry> = emptyList(),
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

enum class Screen {
    HOME,
    CREATE_GAME,
    JOIN_GAME,
    LOBBY,
    BOARD,
    PLAYER_DETAIL,
    COMBAT,
    CATALOG,
    SETTINGS,
    AUTH, // Login/Register
    PROFILE,
    LEADERBOARD,
    HISTORY
}

sealed class GameUiEvent {
    data class ShowError(val message: String) : GameUiEvent()
    data class ShowSuccess(val message: String) : GameUiEvent()
    data class ShowMessage(val message: String) : GameUiEvent()
    data object PlaySound : GameUiEvent()
}

// Response from server for available games
@kotlinx.serialization.Serializable
data class GamesListResponse(
    val type: String,
    val games: List<ServerGame>
)

@kotlinx.serialization.Serializable
data class ServerGame(
    val joinCode: String,
    val hostName: String,
    val playerCount: Int,
    val maxPlayers: Int,
    val createdAt: Long = 0
)

/**
 * Convert technical error messages to user-friendly Spanish messages.
 */
private fun getFriendlyErrorMessage(error: Throwable?): String {
    val message = error?.message?.lowercase() ?: ""
    return when {
        "timeout" in message -> "No se pudo conectar. Comprueba tu conexión a internet y vuelve a intentarlo."
        "refused" in message -> "El servidor no está disponible. Inténtalo más tarde."
        "host" in message && "resolve" in message -> "No se encuentra el servidor. Comprueba tu conexión."
        "closed" in message || "reset" in message -> "Se perdió la conexión. Vuelve a intentarlo."
        "unauthorized" in message -> "No tienes permiso para unirte a esta partida."
        "not found" in message || "404" in message -> "La partida ya no existe."
        "full" in message -> "La partida está llena."
        else -> "Error de conexión. Vuelve a intentarlo."
    }
}
