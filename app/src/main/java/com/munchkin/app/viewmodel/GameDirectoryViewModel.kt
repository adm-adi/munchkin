package com.munchkin.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.AppConfig
import com.munchkin.app.MunchkinApp
import com.munchkin.app.data.GameDirectoryDataSource
import com.munchkin.app.data.HostedGamesRepository
import com.munchkin.app.data.SessionManager
import com.munchkin.app.network.ApiClient
import com.munchkin.app.network.DiscoveredGame
import com.munchkin.app.network.HostedGame
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GameDirectoryViewModel(
    private val repository: GameDirectoryDataSource = HostedGamesRepository(
        apiClient = ApiClient(),
        sessionManager = SessionManager(MunchkinApp.context),
        serverUrl = AppConfig.SERVER_URL
    ),
    private val closeResources: () -> Unit = { (repository as? Closeable)?.close() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameDirectoryUiState())
    val uiState: StateFlow<GameDirectoryUiState> = _uiState.asStateFlow()

    fun discoverGames() {
        if (_uiState.value.isDiscovering) return
        _uiState.update {
            it.copy(isDiscovering = true, error = null, discoveredGames = emptyList())
        }

        viewModelScope.launch {
            repository.discoverGames()
                .onSuccess { games ->
                    _uiState.update {
                        it.copy(isDiscovering = false, discoveredGames = games)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isDiscovering = false, error = error.message)
                    }
                }
        }
    }

    fun loadHostedGames() {
        if (_uiState.value.isLoadingHostedGames) return
        _uiState.update { it.copy(isLoadingHostedGames = true, error = null) }

        viewModelScope.launch {
            repository.getHostedGames()
                .onSuccess { games ->
                    _uiState.update {
                        it.copy(isLoadingHostedGames = false, hostedGames = games)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingHostedGames = false, error = error.message)
                    }
                }
        }
    }

    fun deleteHostedGame(gameId: String) {
        if (_uiState.value.isLoadingHostedGames) return
        _uiState.update { it.copy(isLoadingHostedGames = true, error = null) }

        viewModelScope.launch {
            repository.deleteHostedGame(gameId)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            isLoadingHostedGames = false,
                            hostedGames = state.hostedGames.filterNot { it.gameId == gameId }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingHostedGames = false, error = error.message)
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clear() {
        _uiState.value = GameDirectoryUiState()
    }

    override fun onCleared() {
        super.onCleared()
        closeResources()
    }
}

data class GameDirectoryUiState(
    val discoveredGames: List<DiscoveredGame> = emptyList(),
    val hostedGames: List<HostedGame> = emptyList(),
    val isDiscovering: Boolean = false,
    val isLoadingHostedGames: Boolean = false,
    val error: String? = null
)
