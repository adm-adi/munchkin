package com.munchkin.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.data.SessionManager
import com.munchkin.app.di.GameSessionCoordinator
import com.munchkin.app.di.StringResolver
import com.munchkin.app.network.ErrorCode
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.network.HostedGame
import com.munchkin.app.network.LeaderboardEntry
import com.munchkin.app.network.LeaderboardSelf
import com.munchkin.app.network.PlayerTotals
import com.munchkin.app.network.ServerConfig
import com.munchkin.app.network.ServerErrorException
import com.munchkin.app.network.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Handles authentication, profile management, account deletion, history, leaderboard, and hosted games.
 */
class AuthViewModel internal constructor(
    private val sessionStore: SessionStore,
    private val remoteGateway: RemoteGateway,
    private val sessionCoordinator: GameSessionCoordinator,
    private val stringResolver: StringResolver
) : ViewModel() {

    constructor(
        sessionManager: SessionManager,
        clientFactory: () -> GameClient,
        sessionCoordinator: GameSessionCoordinator,
        stringResolver: StringResolver
    ) : this(
        sessionStore = SessionManagerStore(sessionManager),
        remoteGateway = GameClientRemoteGateway(clientFactory),
        sessionCoordinator = sessionCoordinator,
        stringResolver = stringResolver
    )

    val userProfile: StateFlow<UserProfile?> = sessionCoordinator.userProfile
    val hostedGames: StateFlow<List<HostedGame>> = sessionCoordinator.hostedGames

    private val _gameHistory = MutableStateFlow<List<GameHistoryItem>>(emptyList())
    val gameHistory: StateFlow<List<GameHistoryItem>> = _gameHistory.asStateFlow()

    private val _playerTotals = MutableStateFlow(PlayerTotals())
    val playerTotals: StateFlow<PlayerTotals> = _playerTotals.asStateFlow()

    private val _historyError = MutableStateFlow<String?>(null)
    val historyError: StateFlow<String?> = _historyError.asStateFlow()

    private val _leaderboard = MutableStateFlow<List<LeaderboardEntry>>(emptyList())
    val leaderboard: StateFlow<List<LeaderboardEntry>> = _leaderboard.asStateFlow()

    private val _leaderboardSelf = MutableStateFlow<LeaderboardSelf?>(null)
    val leaderboardSelf: StateFlow<LeaderboardSelf?> = _leaderboardSelf.asStateFlow()

    private val _leaderboardError = MutableStateFlow<String?>(null)
    val leaderboardError: StateFlow<String?> = _leaderboardError.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        if (sessionCoordinator.userProfile.value == null) {
            sessionStore.getSession()?.let { savedProfile ->
                sessionCoordinator.setUserProfile(savedProfile)
            }
        }
    }

    /**
     * Hydrates the cached user session and verifies the stored JWT with the server on startup.
     */
    fun refreshSessionOnStartup() {
        val savedProfile = sessionStore.getSession()
        if (savedProfile != null && sessionCoordinator.userProfile.value == null) {
            sessionCoordinator.setUserProfile(savedProfile)
        }
        val token = sessionStore.getAuthToken() ?: return

        viewModelScope.launch {
            try {
                val result = remoteGateway.loginWithToken(ServerConfig.WS_URL, token)
                result.fold(
                    onSuccess = { auth ->
                        sessionStore.saveSession(auth.user)
                        auth.token?.let { rotated -> sessionStore.saveAuthToken(rotated) }
                        sessionCoordinator.setUserProfile(auth.user)
                        fetchHostedGamesInternal(auth.token ?: token)
                    },
                    onFailure = { err ->
                        val isAuthFailure = err is ServerErrorException &&
                            err.code in setOf(ErrorCode.AUTH_FAILED, ErrorCode.UNAUTHORIZED, ErrorCode.FORBIDDEN)
                        if (isAuthFailure) {
                            sessionStore.clearSession()
                            sessionCoordinator.setUserProfile(null)
                            sessionCoordinator.setHostedGames(emptyList())
                            sessionCoordinator.emitEvent(
                                GameUiEvent.ShowMessage(stringResolver.get(R.string.error_session_expired))
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun login(email: String, password: String) {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = remoteGateway.login(ServerConfig.WS_URL, email.trim(), password)
                result.fold(
                    onSuccess = { auth ->
                        sessionStore.saveSession(auth.user)
                        val activeToken = auth.token?.also { sessionStore.saveAuthToken(it) }
                            ?: sessionStore.getAuthToken()
                        sessionCoordinator.setUserProfile(auth.user)
                        if (activeToken != null) {
                            fetchHostedGamesInternal(activeToken)
                        }
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowSuccess(
                                stringResolver.get(R.string.welcome_back_user, auth.user.username)
                            )
                        )
                    },
                    onFailure = { err ->
                        _error.value = getFriendlyErrorMessage(err, stringResolver)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun register(
        username: String,
        email: String,
        password: String,
        avatarId: Int = 0,
        gender: Gender = Gender.M
    ) {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = remoteGateway.register(
                    serverUrl = ServerConfig.WS_URL,
                    username = username.trim(),
                    email = email.trim(),
                    password = password,
                    avatarId = avatarId,
                    gender = gender
                )
                result.fold(
                    onSuccess = { auth ->
                        sessionStore.saveSession(auth.user)
                        auth.token?.let { sessionStore.saveAuthToken(it) }
                        sessionCoordinator.setUserProfile(auth.user)
                        sessionCoordinator.setHostedGames(emptyList())
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowSuccess(
                                stringResolver.get(R.string.welcome_user, auth.user.username)
                            )
                        )
                    },
                    onFailure = { err ->
                        _error.value = getFriendlyErrorMessage(err, stringResolver)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun logout() {
        sessionStore.clearSession()
        sessionCoordinator.setUserProfile(null)
        sessionCoordinator.setHostedGames(emptyList())
        _gameHistory.value = emptyList()
        _playerTotals.value = PlayerTotals()
        _historyError.value = null
        _leaderboard.value = emptyList()
        _leaderboardSelf.value = null
        _leaderboardError.value = null
        _error.value = null
    }

    fun updateProfile(
        username: String? = null,
        password: String? = null,
        avatarId: Int? = null,
        gender: Gender? = null,
        currentPassword: String? = null
    ) {
        if (_isLoading.value) return
        val token = sessionStore.getAuthToken()
        val currentUser = sessionCoordinator.userProfile.value
        if (token == null || currentUser == null) {
            val expiredMsg = stringResolver.get(R.string.error_session_expired)
            _error.value = expiredMsg
            sessionCoordinator.tryEmitEvent(GameUiEvent.ShowError(expiredMsg))
            return
        }

        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = remoteGateway.updateProfile(
                    serverUrl = ServerConfig.WS_URL,
                    userId = currentUser.id,
                    username = username,
                    password = password,
                    token = token,
                    avatarId = avatarId,
                    gender = gender,
                    currentPassword = currentPassword
                )
                result.fold(
                    onSuccess = { updated ->
                        sessionStore.saveSession(updated.user)
                        updated.token?.let { rotated -> sessionStore.saveAuthToken(rotated) }
                        sessionCoordinator.setUserProfile(updated.user)
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowMessage(stringResolver.get(R.string.profile_updated))
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
                _isLoading.value = false
            }
        }
    }

    fun deleteAccount(password: String) {
        if (_isLoading.value) return
        val token = sessionStore.getAuthToken()
        if (token == null) {
            val expiredMsg = stringResolver.get(R.string.error_session_expired)
            _error.value = expiredMsg
            sessionCoordinator.tryEmitEvent(GameUiEvent.ShowError(expiredMsg))
            return
        }

        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = remoteGateway.deleteAccount(ServerConfig.WS_URL, password, token)
                result.fold(
                    onSuccess = {
                        sessionStore.clearSession()
                        sessionCoordinator.setUserProfile(null)
                        sessionCoordinator.setHostedGames(emptyList())
                        sessionCoordinator.clearActiveSession(closeClient = true)
                        _gameHistory.value = emptyList()
                        _playerTotals.value = PlayerTotals()
                        _historyError.value = null
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowSuccess(stringResolver.get(R.string.account_deleted_success))
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
                _isLoading.value = false
            }
        }
    }

    fun loadHistory() {
        val currentUser = sessionCoordinator.userProfile.value
        val token = sessionStore.getAuthToken()
        if (currentUser == null || token == null) {
            _historyError.value = stringResolver.get(R.string.error_session_expired)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _historyError.value = null
            try {
                val result = remoteGateway.getHistory(ServerConfig.WS_URL, currentUser.id, token)
                result.fold(
                    onSuccess = { history ->
                        _gameHistory.value = history.games
                        _playerTotals.value = history.stats
                    },
                    onFailure = { err ->
                        val friendly = getFriendlyErrorMessage(err, stringResolver)
                        _historyError.value = friendly
                        sessionCoordinator.emitEvent(GameUiEvent.ShowError(friendly))
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadLeaderboard() {
        viewModelScope.launch {
            _isLoading.value = true
            _leaderboardError.value = null
            try {
                val result = remoteGateway.getLeaderboard(
                    serverUrl = ServerConfig.WS_URL,
                    authToken = sessionStore.getAuthToken()
                )
                result.fold(
                    onSuccess = { data ->
                        _leaderboard.value = data.leaderboard
                        _leaderboardSelf.value = data.me
                    },
                    onFailure = { err ->
                        val friendly = getFriendlyErrorMessage(err, stringResolver)
                        _leaderboardError.value = friendly
                        sessionCoordinator.emitEvent(GameUiEvent.ShowError(friendly))
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun fetchHostedGames() {
        val token = sessionStore.getAuthToken() ?: return
        viewModelScope.launch {
            fetchHostedGamesInternal(token)
        }
    }

    fun deleteHostedGame(gameIdOrJoinCode: String) {
        val token = sessionStore.getAuthToken()
        if (token == null) {
            sessionCoordinator.tryEmitEvent(
                GameUiEvent.ShowError(stringResolver.get(R.string.error_session_expired))
            )
            return
        }
        val targetGameId = sessionCoordinator.hostedGames.value
            .firstOrNull { it.gameId == gameIdOrJoinCode || it.joinCode.equals(gameIdOrJoinCode, ignoreCase = true) }
            ?.gameId ?: gameIdOrJoinCode

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = remoteGateway.deleteHostedGame(ServerConfig.WS_URL, token, targetGameId)
                result.fold(
                    onSuccess = {
                        sessionCoordinator.setHostedGames(
                            sessionCoordinator.hostedGames.value.filterNot {
                                it.gameId == targetGameId || it.joinCode.equals(gameIdOrJoinCode, ignoreCase = true)
                            }
                        )
                        sessionCoordinator.emitEvent(
                            GameUiEvent.ShowSuccess(stringResolver.get(R.string.game_deleted_ok))
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
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
        _historyError.value = null
        _leaderboardError.value = null
    }

    private suspend fun fetchHostedGamesInternal(token: String) {
        try {
            remoteGateway.getHostedGames(ServerConfig.WS_URL, token).onSuccess { games ->
                sessionCoordinator.setHostedGames(games)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}
