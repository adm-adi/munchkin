package com.munchkin.app.viewmodel

import androidx.lifecycle.viewModelScope
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R
import com.munchkin.app.network.GameClient
import com.munchkin.app.updater.UpdateChecker
import com.munchkin.app.updater.UpdateResult
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

fun GameViewModel.dismissUpdate() {
    _updateInfo.value = null
}

fun GameViewModel.downloadUpdate() {
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

fun GameViewModel.loadHistory() {
    val user = _uiState.value.userProfile ?: return
    viewModelScope.launch {
        try {
            _uiState.update { it.copy(isLoading = true) }
            val client = GameClient()
            val result = client.getHistory(GameViewModel.SERVER_URL, user.id)
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

fun GameViewModel.loadLeaderboard() {
    if (_uiState.value.isLoading) return

    _uiState.update { it.copy(isLoading = true) }

    viewModelScope.launch {
        GameClient().getLeaderboard(GameViewModel.SERVER_URL)
            .onSuccess { leaderboard ->
                _uiState.update { it.copy(leaderboard = leaderboard, isLoading = false) }
            }
            .onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
    }
}

fun GameViewModel.updateProfile(username: String?, pass: String?) {
    val currentUser = _uiState.value.userProfile ?: return
    val token = sessionManager?.getAuthToken() ?: run {
        _uiState.update { it.copy(error = MunchkinApp.context.getString(R.string.error_session_expired)) }
        return
    }
    if (username.isNullOrBlank() && pass.isNullOrBlank()) return

    _uiState.update { it.copy(isLoading = true, error = null) }

    viewModelScope.launch {
        val result = GameClient().updateProfile(GameViewModel.SERVER_URL, currentUser.id, username, pass, token)
        if (result.isSuccess) {
            val updatedUser = result.getOrThrow()
            _uiState.update { it.copy(userProfile = updatedUser, isLoading = false, error = null) }
            sessionManager?.saveSession(updatedUser)
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
            val client = GameClient()
            val result = client.getHostedGames(GameViewModel.SERVER_URL, token)

            if (result.isSuccess) {
                val games = result.getOrNull() ?: emptyList()
                _hostedGames.value = games

                if (games.isNotEmpty()) {
                    android.util.Log.d("GameViewModel", "Fetched ${games.size} hosted games")
                }
            }
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
            val client = GameClient()
            val result = client.deleteHostedGame(GameViewModel.SERVER_URL, token, gameId)

            if (result.isSuccess) {
                _hostedGames.update { list -> list.filter { it.gameId != gameId } }
                _events.emit(GameUiEvent.ShowSuccess("Partida eliminada"))
            } else {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message) }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message) }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}
