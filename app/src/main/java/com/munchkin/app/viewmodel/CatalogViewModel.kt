package com.munchkin.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.R
import com.munchkin.app.data.SessionManager
import com.munchkin.app.di.StringResolver
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages monster catalog search, pagination, and global catalog monster creation.
 */
class CatalogViewModel internal constructor(
    private val remoteGateway: RemoteGateway,
    private val sessionStore: SessionStore,
    private val stringResolver: StringResolver
) : ViewModel() {

    constructor(
        clientFactory: () -> GameClient,
        sessionManager: SessionManager,
        stringResolver: StringResolver
    ) : this(
        remoteGateway = GameClientRemoteGateway(clientFactory),
        sessionStore = SessionManagerStore(sessionManager),
        stringResolver = stringResolver
    )

    private val _searchResults = MutableStateFlow<List<CatalogMonster>>(emptyList())
    val searchResults: StateFlow<List<CatalogMonster>> = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val canCreateMonster: Boolean
        get() = sessionStore.getAuthToken() != null

    private var searchJob: Job? = null
    private var currentQuery: String = ""
    private var currentExpansion: String? = null
    private var currentIsUndead: Boolean? = null
    private var currentMinLevel: Int? = null
    private var currentMaxLevel: Int? = null
    private var currentOffset: Int = 0

    fun search(
        query: String,
        expansion: String? = null,
        isUndead: Boolean? = currentIsUndead,
        minLevel: Int? = currentMinLevel,
        maxLevel: Int? = currentMaxLevel,
        resetPage: Boolean = true,
        debounceMs: Long = 350L
    ) {
        searchJob?.cancel()

        val trimmed = query.trim()
        if (resetPage && trimmed.length == 1) {
            return
        }

        if (resetPage) {
            currentQuery = trimmed
            currentExpansion = expansion
            currentIsUndead = isUndead
            currentMinLevel = minLevel
            currentMaxLevel = maxLevel
            currentOffset = 0
            _hasMore.value = true
        }

        searchJob = viewModelScope.launch {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            if (resetPage) {
                _isLoading.value = true
            } else {
                _isLoadingMore.value = true
            }
            _error.value = null

            try {
                val effectiveQuery = if (currentQuery.isBlank()) "__ALL__" else currentQuery
                val authToken = sessionStore.getAuthToken()
                var result = remoteGateway.searchMonsters(
                    serverUrl = ServerConfig.WS_URL,
                    query = effectiveQuery,
                    expansion = currentExpansion,
                    limit = PAGE_SIZE,
                    offset = currentOffset,
                    isUndead = currentIsUndead,
                    minLevel = currentMinLevel,
                    maxLevel = currentMaxLevel,
                    authToken = authToken
                )

                // Compatibility fallback for servers that do not recognize __ALL__
                if (
                    result.isSuccess &&
                    result.getOrNull().isNullOrEmpty() &&
                    currentQuery.isBlank() &&
                    currentIsUndead == null &&
                    currentMinLevel == null &&
                    currentMaxLevel == null &&
                    currentOffset == 0
                ) {
                    result = remoteGateway.searchMonsters(
                        serverUrl = ServerConfig.WS_URL,
                        query = "an",
                        expansion = currentExpansion,
                        limit = PAGE_SIZE,
                        offset = 0,
                        authToken = authToken
                    )
                }

                result.fold(
                    onSuccess = { page ->
                        _searchResults.value = if (resetPage) {
                            page
                        } else {
                            (_searchResults.value + page).distinctBy { it.id }
                        }
                        currentOffset += page.size
                        _hasMore.value = page.size >= PAGE_SIZE
                    },
                    onFailure = { err ->
                        _error.value = getFriendlyErrorMessage(err, stringResolver)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isLoading.value = false
                _isLoadingMore.value = false
            }
        }
    }

    fun loadMore() {
        if (_isLoading.value || _isLoadingMore.value || !_hasMore.value) return
        search(
            query = currentQuery,
            expansion = currentExpansion,
            isUndead = currentIsUndead,
            minLevel = currentMinLevel,
            maxLevel = currentMaxLevel,
            resetPage = false,
            debounceMs = 0L
        )
    }

    fun createMonster(
        name: String,
        level: Int,
        modifier: Int,
        isUndead: Boolean,
        treasures: Int,
        levels: Int,
        badStuff: String,
        expansion: String = "custom",
        onCompleted: ((Result<CatalogMonster>) -> Unit)? = null
    ) {
        val token = sessionStore.getAuthToken()
        if (token == null) {
            val expiredMsg = stringResolver.get(R.string.error_session_expired)
            _error.value = expiredMsg
            onCompleted?.invoke(Result.failure(IllegalStateException(expiredMsg)))
            return
        }

        val cleanName = name.trim().take(80)
        val cleanBadStuff = badStuff.trim().take(200)
        val monster = CatalogMonster(
            name = cleanName,
            level = level.coerceIn(1, 20),
            modifier = modifier.coerceIn(-10, 10),
            treasures = treasures.coerceIn(0, 10),
            levels = levels.coerceIn(1, 5),
            isUndead = isUndead,
            badStuff = cleanBadStuff,
            expansion = expansion
        )

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            var refreshLaunched = false
            try {
                val result = remoteGateway.addMonsterToCatalog(ServerConfig.WS_URL, monster, token)
                result.fold(
                    onSuccess = { created ->
                        _statusMessage.value = stringResolver.get(R.string.monster_created, created.name)
                        refreshLaunched = true
                        search(currentQuery, currentExpansion, resetPage = true, debounceMs = 0L)
                        onCompleted?.invoke(Result.success(created))
                    },
                    onFailure = { err ->
                        _error.value = getFriendlyErrorMessage(err, stringResolver)
                        onCompleted?.invoke(Result.failure(err))
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (!refreshLaunched) {
                    _isLoading.value = false
                }
            }
        }
    }

    fun updateMonster(
        monsterId: String,
        name: String,
        level: Int,
        modifier: Int,
        isUndead: Boolean,
        treasures: Int,
        levels: Int,
        badStuff: String,
        expansion: String = "custom",
        onCompleted: ((Result<CatalogMonster>) -> Unit)? = null
    ) {
        val token = sessionStore.getAuthToken()
        if (token == null) {
            val expiredMsg = stringResolver.get(R.string.error_session_expired)
            _error.value = expiredMsg
            onCompleted?.invoke(Result.failure(IllegalStateException(expiredMsg)))
            return
        }

        val monster = CatalogMonster(
            id = monsterId,
            name = name.trim().take(80),
            level = level.coerceIn(1, 20),
            modifier = modifier.coerceIn(-10, 10),
            treasures = treasures.coerceIn(0, 10),
            levels = levels.coerceIn(1, 5),
            isUndead = isUndead,
            badStuff = badStuff.trim().take(200),
            expansion = expansion
        )

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            var refreshLaunched = false
            try {
                val result = remoteGateway.updateMonsterInCatalog(ServerConfig.WS_URL, monsterId, monster, token)
                result.fold(
                    onSuccess = { updated ->
                        _statusMessage.value = stringResolver.get(R.string.catalog_monster_updated, updated.name)
                        refreshLaunched = true
                        search(currentQuery, currentExpansion, currentIsUndead, currentMinLevel, currentMaxLevel, resetPage = true, debounceMs = 0L)
                        onCompleted?.invoke(Result.success(updated))
                    },
                    onFailure = { err ->
                        _error.value = getFriendlyErrorMessage(err, stringResolver)
                        onCompleted?.invoke(Result.failure(err))
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (!refreshLaunched) {
                    _isLoading.value = false
                }
            }
        }
    }

    fun deleteMonster(
        monsterId: String,
        onCompleted: ((Result<String>) -> Unit)? = null
    ) {
        val token = sessionStore.getAuthToken()
        if (token == null) {
            val expiredMsg = stringResolver.get(R.string.error_session_expired)
            _error.value = expiredMsg
            onCompleted?.invoke(Result.failure(IllegalStateException(expiredMsg)))
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = remoteGateway.deleteMonsterFromCatalog(ServerConfig.WS_URL, monsterId, token)
                result.fold(
                    onSuccess = { deletedId ->
                        _searchResults.value = _searchResults.value.filterNot { it.id == deletedId }
                        _statusMessage.value = stringResolver.get(R.string.catalog_monster_deleted)
                        onCompleted?.invoke(Result.success(deletedId))
                    },
                    onFailure = { err ->
                        _error.value = getFriendlyErrorMessage(err, stringResolver)
                        onCompleted?.invoke(Result.failure(err))
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}
