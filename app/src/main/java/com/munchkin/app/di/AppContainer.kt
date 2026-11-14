package com.munchkin.app.di

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId
import com.munchkin.app.data.AppSettingsPrefs
import com.munchkin.app.data.GameRepository
import com.munchkin.app.data.SavedGame
import com.munchkin.app.data.SessionManager
import com.munchkin.app.data.TutorialPrefs
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.HostedGame
import com.munchkin.app.network.UserProfile
import com.munchkin.app.update.UpdateChecker
import com.munchkin.app.viewmodel.AuthViewModel
import com.munchkin.app.viewmodel.CatalogViewModel
import com.munchkin.app.viewmodel.GameClientSessionConnection
import com.munchkin.app.viewmodel.GameSessionViewModel
import com.munchkin.app.viewmodel.GameUiEvent
import com.munchkin.app.viewmodel.LobbyViewModel
import com.munchkin.app.viewmodel.SessionConnection
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Resolves Android string resources without holding Context references in ViewModels.
 */
fun interface StringResolver {
    fun get(@StringRes resId: Int, vararg args: Any): String
}

class AndroidStringResolver(
    context: Context
) : StringResolver {
    private val appContext = context.applicationContext

    override fun get(@StringRes resId: Int, vararg args: Any): String {
        return if (args.isEmpty()) {
            appContext.getString(resId)
        } else {
            appContext.getString(resId, *args)
        }
    }
}

/**
 * Shared singleton holder owned by [AppContainer] coordinating cross-ViewModel state and one-shot events.
 */
class GameSessionCoordinator {
    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    private val _savedGame = MutableStateFlow<SavedGame?>(null)
    val savedGame: StateFlow<SavedGame?> = _savedGame.asStateFlow()

    private val _hostedGames = MutableStateFlow<List<HostedGame>>(emptyList())
    val hostedGames: StateFlow<List<HostedGame>> = _hostedGames.asStateFlow()

    private val _activeClient = MutableStateFlow<GameClient?>(null)
    val activeClient: StateFlow<GameClient?> = _activeClient.asStateFlow()

    private val _activeSession = MutableStateFlow<SessionConnection?>(null)
    internal val activeSession: StateFlow<SessionConnection?> = _activeSession.asStateFlow()

    private val _gameState = MutableStateFlow<GameState?>(null)
    val gameState: StateFlow<GameState?> = _gameState.asStateFlow()

    private val _myPlayerId = MutableStateFlow<PlayerId?>(null)
    val myPlayerId: StateFlow<PlayerId?> = _myPlayerId.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _serverTimeOffsetMs = MutableStateFlow(0L)
    val serverTimeOffsetMs: StateFlow<Long> = _serverTimeOffsetMs.asStateFlow()

    private val _isConnectingOrLoading = MutableStateFlow(false)
    val isConnectingOrLoading: StateFlow<Boolean> = _isConnectingOrLoading.asStateFlow()

    private val _events = MutableSharedFlow<GameUiEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<GameUiEvent> = _events.asSharedFlow()

    fun setUserProfile(profile: UserProfile?) {
        _userProfile.value = profile
    }

    fun setSavedGame(savedGame: SavedGame?) {
        _savedGame.value = savedGame
    }

    fun setHostedGames(games: List<HostedGame>) {
        _hostedGames.value = games
    }

    fun setConnectingOrLoading(loading: Boolean) {
        _isConnectingOrLoading.value = loading
    }

    fun attachActiveClient(
        client: GameClient,
        initialState: GameState,
        playerId: PlayerId?
    ) {
        attachActiveSession(GameClientSessionConnection(client), initialState, playerId)
    }

    internal fun attachActiveSession(
        session: SessionConnection,
        initialState: GameState,
        playerId: PlayerId?
    ) {
        val prevSession = _activeSession.value
        if (prevSession != null && prevSession !== session) {
            runCatching { prevSession.close() }
        }
        _activeSession.value = session
        _activeClient.value = session.rawClient
        _gameState.value = initialState
        _myPlayerId.value = playerId
        _connectionState.value = session.connectionState.value
        _reconnectAttempt.value = session.reconnectAttempt.value
        _serverTimeOffsetMs.value = session.serverTimeOffsetMs.value
    }

    fun updateGameState(state: GameState?) {
        _gameState.value = state
    }

    fun updateMyPlayerId(playerId: PlayerId?) {
        _myPlayerId.value = playerId
    }

    fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun updateReconnectAttempt(attempt: Int) {
        _reconnectAttempt.value = attempt
    }

    fun updateServerTimeOffsetMs(offsetMs: Long) {
        _serverTimeOffsetMs.value = offsetMs
    }

    fun clearActiveSession(closeClient: Boolean = true) {
        val session = _activeSession.value
        if (closeClient && session != null) {
            runCatching { session.close() }
        }
        _activeSession.value = null
        _activeClient.value = null
        _gameState.value = null
        _myPlayerId.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _reconnectAttempt.value = 0
        _serverTimeOffsetMs.value = 0L
    }

    suspend fun emitEvent(event: GameUiEvent) {
        _events.emit(event)
    }

    fun tryEmitEvent(event: GameUiEvent): Boolean = _events.tryEmit(event)
}

/**
 * Explicit dependency container replacing static Application context singletons.
 */
interface AppContainer {
    val sessionManager: SessionManager
    val gameRepository: GameRepository
    val tutorialPrefs: TutorialPrefs
    val appSettingsPrefs: AppSettingsPrefs
    val updateChecker: UpdateChecker
    val stringResolver: StringResolver
    val clientFactory: () -> GameClient
    val sessionCoordinator: GameSessionCoordinator
    val viewModelFactory: ViewModelProvider.Factory
}

class DefaultAppContainer(
    appContext: Context
) : AppContainer {
    private val context = appContext.applicationContext

    override val sessionManager: SessionManager by lazy {
        SessionManager(context)
    }

    override val gameRepository: GameRepository by lazy {
        GameRepository(context)
    }

    override val tutorialPrefs: TutorialPrefs by lazy {
        TutorialPrefs(context)
    }

    override val appSettingsPrefs: AppSettingsPrefs by lazy {
        AppSettingsPrefs(context)
    }

    override val updateChecker: UpdateChecker by lazy {
        UpdateChecker(context)
    }

    override val stringResolver: StringResolver by lazy {
        AndroidStringResolver(context)
    }

    override val clientFactory: () -> GameClient = { GameClient() }

    override val sessionCoordinator: GameSessionCoordinator by lazy {
        GameSessionCoordinator()
    }

    override val viewModelFactory: ViewModelProvider.Factory by lazy {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when {
                    modelClass.isAssignableFrom(AuthViewModel::class.java) -> AuthViewModel(
                        sessionManager = sessionManager,
                        clientFactory = clientFactory,
                        sessionCoordinator = sessionCoordinator,
                        stringResolver = stringResolver
                    ) as T

                    modelClass.isAssignableFrom(LobbyViewModel::class.java) -> LobbyViewModel(
                        sessionManager = sessionManager,
                        gameRepository = gameRepository,
                        updateChecker = updateChecker,
                        appSettingsPrefs = appSettingsPrefs,
                        clientFactory = clientFactory,
                        sessionCoordinator = sessionCoordinator,
                        stringResolver = stringResolver
                    ) as T

                    modelClass.isAssignableFrom(GameSessionViewModel::class.java) -> GameSessionViewModel(
                        sessionManager = sessionManager,
                        gameRepository = gameRepository,
                        clientFactory = clientFactory,
                        sessionCoordinator = sessionCoordinator,
                        stringResolver = stringResolver
                    ) as T

                    modelClass.isAssignableFrom(CatalogViewModel::class.java) -> CatalogViewModel(
                        clientFactory = clientFactory,
                        sessionManager = sessionManager,
                        stringResolver = stringResolver
                    ) as T

                    else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
