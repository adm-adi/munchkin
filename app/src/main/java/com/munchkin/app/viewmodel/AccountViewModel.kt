package com.munchkin.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.AppConfig
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R
import com.munchkin.app.data.AccountSessionStore
import com.munchkin.app.data.AuthDataSource
import com.munchkin.app.data.AuthRepository
import com.munchkin.app.data.ProfileDataSource
import com.munchkin.app.data.ProfileRepository
import com.munchkin.app.data.SessionManager
import com.munchkin.app.network.ApiClient
import com.munchkin.app.network.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AccountViewModel(
    private val sessionStore: AccountSessionStore,
    private val authRepository: AuthDataSource,
    private val profileRepository: ProfileDataSource,
    private val unknownErrorMessage: () -> String = {
        MunchkinApp.context.getString(R.string.error_unknown)
    },
    private val closeResources: () -> Unit = {}
) : ViewModel() {
    constructor() : this(createDefaultAccountDependencies())

    private constructor(dependencies: AccountViewModelDependencies) : this(
        sessionStore = dependencies.sessionStore,
        authRepository = dependencies.authRepository,
        profileRepository = dependencies.profileRepository,
        unknownErrorMessage = dependencies.unknownErrorMessage,
        closeResources = dependencies.closeResources
    )

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        restoreSession()
    }

    fun register(username: String, email: String, password: String) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            authRepository.register(username, email, password)
                .onSuccess { auth ->
                    _uiState.update {
                        it.copy(isLoading = false, userProfile = auth.user, error = null)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun login(email: String, password: String) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            authRepository.login(email, password)
                .onSuccess { auth ->
                    _uiState.update {
                        it.copy(isLoading = false, userProfile = auth.user, error = null)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun logout() {
        authRepository.logout()
        _uiState.value = AccountUiState()
    }

    fun updateProfile(username: String?, password: String?) {
        if (_uiState.value.userProfile == null) return
        if (username.isNullOrBlank() && password.isNullOrBlank()) return

        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            profileRepository.updateProfile(username, password)
                .onSuccess { updated ->
                    _uiState.update {
                        it.copy(isLoading = false, userProfile = updated, error = null)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: unknownErrorMessage()
                        )
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun restoreSession() {
        val savedProfile = sessionStore.getSession()
        val savedToken = sessionStore.getAuthToken()
        if (savedProfile != null) {
            _uiState.update { it.copy(userProfile = savedProfile) }
        }

        if (savedToken == null) return
        viewModelScope.launch {
            authRepository.restore(savedToken)
                .onSuccess { auth ->
                    _uiState.update {
                        it.copy(userProfile = auth.user, error = null)
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeResources()
    }
}

data class AccountUiState(
    val userProfile: UserProfile? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

private data class AccountViewModelDependencies(
    val sessionStore: AccountSessionStore,
    val authRepository: AuthDataSource,
    val profileRepository: ProfileDataSource,
    val unknownErrorMessage: () -> String,
    val closeResources: () -> Unit
)

private fun createDefaultAccountDependencies(): AccountViewModelDependencies {
    val apiClient = ApiClient()
    val sessionManager = SessionManager(MunchkinApp.context)
    return AccountViewModelDependencies(
        sessionStore = sessionManager,
        authRepository = AuthRepository(apiClient, sessionManager, AppConfig.SERVER_URL),
        profileRepository = ProfileRepository(apiClient, sessionManager, AppConfig.SERVER_URL),
        unknownErrorMessage = { MunchkinApp.context.getString(R.string.error_unknown) },
        closeResources = { apiClient.close() }
    )
}
