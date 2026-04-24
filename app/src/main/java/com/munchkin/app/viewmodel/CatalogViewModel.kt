package com.munchkin.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.AppConfig
import com.munchkin.app.MunchkinApp
import com.munchkin.app.data.CatalogDataSource
import com.munchkin.app.data.CatalogRepository
import com.munchkin.app.data.SessionManager
import com.munchkin.app.network.ApiClient
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.network.UserProfile
import java.io.Closeable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CatalogViewModel(
    private val catalogRepository: CatalogDataSource = CatalogRepository(
        apiClient = ApiClient(),
        sessionManager = SessionManager(MunchkinApp.context),
        serverUrl = AppConfig.SERVER_URL
    ),
    private val closeResources: () -> Unit = { (catalogRepository as? Closeable)?.close() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private val _createdMonsters = MutableSharedFlow<CatalogMonster>()
    val createdMonsters: SharedFlow<CatalogMonster> = _createdMonsters.asSharedFlow()

    fun searchMonsters(query: String) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            catalogRepository.searchMonsters(query)
                .onSuccess { results ->
                    _uiState.update {
                        it.copy(isLoading = false, searchResults = results)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun createGlobalMonster(
        name: String,
        level: Int,
        modifier: Int,
        isUndead: Boolean,
        userProfile: UserProfile?,
        fallbackOwnerId: String
    ) {
        val monster = CatalogMonster(
            name = name,
            level = level.coerceIn(1, 20),
            modifier = modifier.coerceIn(-10, 10),
            isUndead = isUndead,
            createdBy = userProfile?.id ?: fallbackOwnerId
        )

        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            catalogRepository.addMonster(monster)
                .onSuccess { created ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            searchResults = (listOf(created) + it.searchResults)
                                .distinctBy(CatalogMonster::id)
                        )
                    }
                    _createdMonsters.emit(created)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun clear() {
        _uiState.value = CatalogUiState()
    }

    override fun onCleared() {
        super.onCleared()
        closeResources()
    }
}

data class CatalogUiState(
    val searchResults: List<CatalogMonster> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
