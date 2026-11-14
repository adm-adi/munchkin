package com.munchkin.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerMeta
import com.munchkin.app.data.AppSettingsPrefs
import com.munchkin.app.data.GameRepository
import com.munchkin.app.data.SavedGame
import com.munchkin.app.data.SessionManager
import com.munchkin.app.di.GameSessionCoordinator
import com.munchkin.app.di.StringResolver
import com.munchkin.app.network.DiscoveredGame
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.HostedGame
import com.munchkin.app.network.JoinCodeParser
import com.munchkin.app.network.ServerConfig
import com.munchkin.app.network.UserProfile
import com.munchkin.app.update.UpdateChecker
import com.munchkin.app.update.UpdateInfo
import com.munchkin.app.update.UpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Handles game discovery, room creation, joining, saved/hosted game resumption, updates, and UI prefs.
 */
class LobbyViewModel internal constructor(
    private val sessionStore: SessionStore,
    private val savedGameStore: SavedGameStore,
    private val updateGateway: UpdateGateway,
    private val largeNumbersPrefs: LargeNumbersPrefs,
    private val remoteGateway: RemoteGateway,
    private val sessionCoordinator: GameSessionCoordinator,
    private val stringResolver: StringResolver
) : ViewModel() {

    constructor(
        sessionManager: SessionManager,
        gameRepository: GameRepository,
        updateChecker: UpdateChecker,
        appSettingsPrefs: AppSettingsPrefs,
        clientFactory: () -> GameClient,
        sessionCoordinator: GameSessionCoordinator,
        stringResolver: StringResolver
    ) : this(
        sessionStore = SessionManagerStore(sessionManager),
        savedGameStore = RoomSavedGameStore(gameRepository),
        updateGateway = AppUpdateGateway(updateChecker),
        largeNumbersPrefs = AppLargeNumbersPrefs(appSettingsPrefs),
        remoteGateway = GameClientRemoteGateway(clientFactory),
        sessionCoordinator = sessionCoordinator,
        stringResolver = stringResolver
    )

    private val _discoveredGames = MutableStateFlow<List<DiscoveredGame>>(emptyList())
    val discoveredGames: StateFlow<List<DiscoveredGame>> = _discoveredGames.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    val savedGame: StateFlow<SavedGame?> = sessionCoordinator.savedGame
    val hostedGames: StateFlow<List<HostedGame>> = sessionCoordinator.hostedGames
    val userProfile: StateFlow<UserProfile?> = sessionCoordinator.userProfile

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    val largeNumbersMode: StateFlow<Boolean> = largeNumbersPrefs.largeNumbersMode

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        savedGameStore.getLatestSavedGame()
            .onEach { saved -> sessionCoordinator.setSavedGame(saved) }
            .launchIn(viewModelScope)
    }

    fun startDiscovery() {
        if (_isDiscovering.value) return
        _isDiscovering.value = true
        viewModelScope.launch {
            try {
                val result = remoteGateway.listGames(ServerConfig.WS_URL)
                result.fold(
                    onSuccess = { games -> _discoveredGames.value = games },
                    onFailure = { err ->
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isDiscovering.value = false
            }
        }
    }

    fun createGame(
        name: String,
        avatarId: Int,
        gender: Gender,
        timerSeconds: Int = 0,
        superMunchkin: Boolean = false
    ) {
        if (_isLoading.value || sessionCoordinator.isConnectingOrLoading.value) return
        setConnectingState(true)
        _error.value = null
        viewModelScope.launch {
            sessionCoordinator.clearActiveSession(closeClient = true)
            try {
                val playerId = PlayerId(UUID.randomUUID().toString())
                val user = sessionCoordinator.userProfile.value
                val playerMeta = PlayerMeta(
                    playerId = playerId,
                    name = name.trim().ifEmpty {
                        user?.username ?: stringResolver.get(R.string.default_host_name)
                    },
                    avatarId = avatarId,
                    gender = gender,
                    userId = user?.id
                )
                val result = remoteGateway.createSessionConnection(
                    serverUrl = ServerConfig.WS_URL,
                    playerMeta = playerMeta,
                    superMunchkin = superMunchkin,
                    turnTimerSeconds = timerSeconds,
                    authToken = sessionStore.getAuthToken()
                )
                result.fold(
                    onSuccess = { (session, initialState) ->
                        GameConnectionSupport.persistAndActivateSession(
                            session = session,
                            initialState = initialState,
                            fallbackPlayerId = playerId,
                            sessionStore = sessionStore,
                            savedGameStore = savedGameStore,
                            sessionCoordinator = sessionCoordinator
                        )
                    },
                    onFailure = { err ->
                        val friendly = getFriendlyErrorMessage(err, stringResolver)
                        _error.value = friendly
                        sessionCoordinator.emitEvent(GameUiEvent.ShowError(friendly))
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                setConnectingState(false)
            }
        }
    }

    fun joinGame(
        joinCode: String,
        name: String,
        avatarId: Int,
        gender: Gender
    ) {
        if (_isLoading.value || sessionCoordinator.isConnectingOrLoading.value) return
        val normalizedCode = JoinCodeParser.extractJoinCode(joinCode)
        if (normalizedCode == null) {
            val invalidMsg = stringResolver.get(R.string.error_invalid_code)
            _error.value = invalidMsg
            sessionCoordinator.tryEmitEvent(GameUiEvent.ShowError(invalidMsg))
            return
        }

        setConnectingState(true)
        _error.value = null
        viewModelScope.launch {
            try {
                val user = sessionCoordinator.userProfile.value
                val playerId = sessionStore.getPlayerId(normalizedCode)
                    ?.let(::PlayerId)
                    ?: PlayerId(UUID.randomUUID().toString())
                val playerMeta = PlayerMeta(
                    playerId = playerId,
                    name = name.trim().ifEmpty {
                        user?.username ?: stringResolver.get(R.string.default_player_name)
                    },
                    avatarId = avatarId,
                    gender = gender,
                    userId = user?.id
                )
                val result = GameConnectionSupport.connectAndAttach(
                    joinCode = normalizedCode,
                    playerMeta = playerMeta,
                    fallbackPlayerId = playerId,
                    sessionStore = sessionStore,
                    savedGameStore = savedGameStore,
                    remoteGateway = remoteGateway,
                    sessionCoordinator = sessionCoordinator
                )
                result.onFailure { err ->
                    val friendly = getFriendlyErrorMessage(err, stringResolver)
                    _error.value = friendly
                    sessionCoordinator.emitEvent(GameUiEvent.ShowError(friendly))
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                setConnectingState(false)
            }
        }
    }

    fun joinDiscoveredGame(
        game: DiscoveredGame,
        name: String,
        avatarId: Int,
        gender: Gender
    ) {
        joinGame(
            joinCode = game.joinCode,
            name = name,
            avatarId = avatarId,
            gender = gender
        )
    }

    fun resumeSavedGame() {
        if (_isLoading.value || sessionCoordinator.isConnectingOrLoading.value) return
        setConnectingState(true)
        _error.value = null
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
                    val friendly = getFriendlyErrorMessage(err, stringResolver)
                    _error.value = friendly
                    sessionCoordinator.emitEvent(GameUiEvent.ShowError(friendly))
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                setConnectingState(false)
            }
        }
    }

    fun resumeHostedGame(joinCode: String) {
        if (_isLoading.value || sessionCoordinator.isConnectingOrLoading.value) return
        val normalizedCode = JoinCodeParser.extractJoinCode(joinCode)
        if (normalizedCode == null) {
            val invalidMsg = stringResolver.get(R.string.error_invalid_code)
            _error.value = invalidMsg
            sessionCoordinator.tryEmitEvent(GameUiEvent.ShowError(invalidMsg))
            return
        }

        setConnectingState(true)
        _error.value = null
        viewModelScope.launch {
            try {
                val user = sessionCoordinator.userProfile.value
                val playerId = sessionStore.getPlayerId(normalizedCode)
                    ?.let(::PlayerId)
                    ?: PlayerId(UUID.randomUUID().toString())
                val playerMeta = PlayerMeta(
                    playerId = playerId,
                    name = user?.username ?: stringResolver.get(R.string.default_host_name),
                    avatarId = user?.avatarId ?: 0,
                    gender = user?.gender ?: Gender.M,
                    userId = user?.id
                )
                val result = GameConnectionSupport.connectAndAttach(
                    joinCode = normalizedCode,
                    playerMeta = playerMeta,
                    fallbackPlayerId = playerId,
                    sessionStore = sessionStore,
                    savedGameStore = savedGameStore,
                    remoteGateway = remoteGateway,
                    sessionCoordinator = sessionCoordinator
                )
                result.onFailure { err ->
                    val friendly = getFriendlyErrorMessage(err, stringResolver)
                    _error.value = friendly
                    sessionCoordinator.emitEvent(GameUiEvent.ShowError(friendly))
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                setConnectingState(false)
            }
        }
    }

    fun deleteSavedGame() {
        viewModelScope.launch {
            savedGameStore.deleteAllSavedGames()
            sessionCoordinator.setSavedGame(null)
        }
    }

    fun checkForUpdates() {
        runUpdateCheck(forceFeedback = false)
    }

    fun forceCheckUpdate() {
        runUpdateCheck(forceFeedback = true)
    }

    fun downloadUpdate() {
        val info = _updateInfo.value ?: return
        if (_isDownloading.value) return
        _isDownloading.value = true
        viewModelScope.launch {
            try {
                val result = updateGateway.downloadAndInstall(info)
                result.fold(
                    onSuccess = { _updateInfo.value = null },
                    onFailure = { err ->
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowError(getFriendlyErrorMessage(err, stringResolver))
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isDownloading.value = false
            }
        }
    }

    fun dismissUpdate() {
        _updateInfo.value = null
    }

    fun setLargeNumbersMode(enabled: Boolean) {
        largeNumbersPrefs.setLargeNumbersMode(enabled)
    }

    fun clearError() {
        _error.value = null
    }

    private fun runUpdateCheck(forceFeedback: Boolean) {
        if (_isCheckingUpdate.value) return
        _isCheckingUpdate.value = true
        viewModelScope.launch {
            try {
                when (val result = updateGateway.checkForUpdate()) {
                    is UpdateResult.UpdateAvailable -> {
                        _updateInfo.value = result.info
                        if (forceFeedback) {
                            sessionCoordinator.emitEvent(
                                GameUiEvent.ShowMessage(
                                    stringResolver.get(R.string.update_available_version, result.info.version)
                                )
                            )
                        }
                    }
                    is UpdateResult.NoUpdate -> {
                        _updateInfo.value = null
                        if (forceFeedback) {
                            sessionCoordinator.emitEvent(
                                GameUiEvent.ShowMessage(stringResolver.get(R.string.update_already_latest))
                            )
                        }
                    }
                    is UpdateResult.Error -> {
                        if (forceFeedback) {
                            sessionCoordinator.emitEvent(
                                GameUiEvent.ShowError(
                                    stringResolver.get(R.string.error_server_generic, result.message)
                                )
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isCheckingUpdate.value = false
            }
        }
    }

    private fun setConnectingState(loading: Boolean) {
        _isLoading.value = loading
        sessionCoordinator.setConnectingOrLoading(loading)
    }
}
