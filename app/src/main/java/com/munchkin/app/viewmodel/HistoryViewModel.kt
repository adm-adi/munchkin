package com.munchkin.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.AppConfig
import com.munchkin.app.MunchkinApp
import com.munchkin.app.data.HistoryDataSource
import com.munchkin.app.data.HistoryRepository
import com.munchkin.app.data.SessionManager
import com.munchkin.app.network.ApiClient
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.network.LeaderboardEntry
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val historyRepository: HistoryDataSource = HistoryRepository(
        apiClient = ApiClient(),
        sessionManager = SessionManager(MunchkinApp.context),
        serverUrl = AppConfig.SERVER_URL
    ),
    private val closeResources: () -> Unit = { (historyRepository as? Closeable)?.close() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    fun loadHistory() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            historyRepository.loadHistory()
                .onSuccess { history ->
                    _uiState.update {
                        it.copy(isLoading = false, gameHistory = history)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun loadLeaderboard() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            historyRepository.loadLeaderboard()
                .onSuccess { leaderboard ->
                    _uiState.update {
                        it.copy(isLoading = false, leaderboard = leaderboard)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clear() {
        _uiState.value = HistoryUiState()
    }

    override fun onCleared() {
        super.onCleared()
        closeResources()
    }
}

data class HistoryUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val gameHistory: List<GameHistoryItem> = emptyList(),
    val leaderboard: List<LeaderboardEntry> = emptyList()
)
