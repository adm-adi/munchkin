package com.munchkin.app.viewmodel

import androidx.lifecycle.viewModelScope
import com.munchkin.app.MunchkinApp
import com.munchkin.app.core.Gender
import com.munchkin.app.R
import com.munchkin.app.network.GameClient
import com.munchkin.app.update.UpdateChecker
import com.munchkin.app.update.UpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun GameViewModel.checkForUpdates() {
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

fun GameViewModel.forceCheckUpdate() {
    if (updateChecker == null) {
        updateChecker = UpdateChecker(MunchkinApp.context)
    }

    _uiState.update { it.copy(isCheckingUpdate = true) }

    viewModelScope.launch {
        try {
            when (val result = updateChecker?.checkForUpdate()) {
                is UpdateResult.UpdateAvailable -> {
                    _updateInfo.value = result.info
                    _events.emit(GameUiEvent.ShowMessage(
                        MunchkinApp.context.getString(R.string.update_available_version, result.info.version)
                    ))
                }
                is UpdateResult.NoUpdate -> {
                    _updateInfo.value = null
                    _events.emit(GameUiEvent.ShowMessage(
                        MunchkinApp.context.getString(R.string.update_already_latest)
                    ))
                }
                is UpdateResult.Error -> {
                    _events.emit(GameUiEvent.ShowMessage(
                        MunchkinApp.context.getString(R.string.error_server_generic, result.message)
                    ))
                }
                null -> {}
            }
        } finally {
            _uiState.update { it.copy(isCheckingUpdate = false) }
        }
    }
}

fun GameViewModel.dismissUpdate() {
    _updateInfo.value = null
}

fun GameViewModel.downloadUpdate() {
    val info = _updateInfo.value ?: return
    val checker = updateChecker ?: return
    if (_isDownloading.value) return

    viewModelScope.launch {
        _isDownloading.value = true
        try {
            val result = checker.downloadAndInstall(info)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message
                    ?: MunchkinApp.context.getString(R.string.error_unknown)
                _events.emit(GameUiEvent.ShowError(msg))
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            _isDownloading.value = false
        }
    }
}

fun GameViewModel.loadHistory() {
    val user = _uiState.value.userProfile ?: return
    // History is private to the account, so the request has to be authenticated.
    val token = sessionManager?.getAuthToken() ?: run {
        _uiState.update {
            it.copy(error = MunchkinApp.context.getString(R.string.error_session_expired))
        }
        return
    }

    viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true) }
        GameClient().use { it.getHistory(GameViewModel.SERVER_URL, user.id, token) }
            .onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        gameHistory = result.games,
                        playerTotals = result.stats
                    )
                }
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(isLoading = false, error = getFriendlyErrorMessage(e))
                }
            }
    }
}

fun GameViewModel.loadLeaderboard() {
    if (_uiState.value.isLoading) return

    _uiState.update { it.copy(isLoading = true) }

    viewModelScope.launch {
        val token = sessionManager?.getAuthToken()

        GameClient().use { it.getLeaderboard(GameViewModel.SERVER_URL, token) }
            .onSuccess { result ->
                _uiState.update {
                    it.copy(
                        leaderboard = result.leaderboard,
                        leaderboardSelf = result.me,
                        isLoading = false
                    )
                }
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(isLoading = false, error = getFriendlyErrorMessage(e))
                }
            }
    }
}

fun GameViewModel.updateProfile(
    username: String? = null,
    pass: String? = null,
    avatarId: Int? = null,
    gender: Gender? = null,
    currentPassword: String? = null
) {
    val currentUser = _uiState.value.userProfile ?: return
    val token = sessionManager?.getAuthToken() ?: run {
        _uiState.update { it.copy(error = MunchkinApp.context.getString(R.string.error_session_expired)) }
        return
    }
    // avatarId 0 is a real slot, so absence is null rather than falsy.
    if (username.isNullOrBlank() && pass.isNullOrBlank() && avatarId == null && gender == null) return

    _uiState.update { it.copy(isLoading = true, error = null) }

    viewModelScope.launch {
        val result = GameClient().use { client ->
            client.updateProfile(
                GameViewModel.SERVER_URL,
                currentUser.id,
                username,
                pass,
                token,
                avatarId,
                gender,
                currentPassword
            )
        }
        if (result.isSuccess) {
            val updated = result.getOrThrow()
            val updatedUser = updated.user
            _uiState.update { it.copy(userProfile = updatedUser, isLoading = false, error = null) }
            sessionManager?.saveSession(updatedUser)
            updated.token?.let { sessionManager?.saveAuthToken(it) }
            _events.emit(GameUiEvent.ShowMessage(MunchkinApp.context.getString(R.string.profile_updated)))
        } else {
            val error = result.exceptionOrNull()?.message ?: MunchkinApp.context.getString(R.string.error_unknown)
            _uiState.update { it.copy(isLoading = false, error = error) }
        }
    }
}

fun GameViewModel.fetchHostedGames() {
    _uiState.value.userProfile ?: return
    val token = sessionManager?.getAuthToken() ?: return

    viewModelScope.launch {
        try {
            val result = GameClient().use { client ->
                client.getHostedGames(GameViewModel.SERVER_URL, token)
            }

            if (result.isSuccess) {
                val games = result.getOrNull() ?: emptyList()
                _hostedGames.value = games

                if (games.isNotEmpty()) {
                    android.util.Log.d("GameViewModel", "Fetched ${games.size} hosted games")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("GameViewModel", "Failed to fetch hosted games", e)
        }
    }
}

fun GameViewModel.deleteHostedGame(gameId: String) {
    val token = sessionManager?.getAuthToken() ?: return

    viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true) }
        try {
            val result = GameClient().use { client ->
                client.deleteHostedGame(GameViewModel.SERVER_URL, token, gameId)
            }

            if (result.isSuccess) {
                _hostedGames.update { list -> list.filter { it.gameId != gameId } }
                _events.emit(GameUiEvent.ShowSuccess(
                    MunchkinApp.context.getString(R.string.game_deleted_ok)
                ))
            } else {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message) }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}
