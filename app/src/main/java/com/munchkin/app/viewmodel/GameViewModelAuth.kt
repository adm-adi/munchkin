package com.munchkin.app.viewmodel

import androidx.lifecycle.viewModelScope
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.ServerConfig
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun GameViewModel.register(username: String, email: String, pass: String) {
    viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val client = GameClient() // Temp instance
            val result = client.register(ServerConfig.WS_URL, username, email, pass)

            if (result.isSuccess) {
                val authData = result.getOrNull()
                if (authData != null) {
                    sessionManager?.saveSession(authData.user)
                    authData.token?.let { sessionManager?.saveAuthToken(it) }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        userProfile = authData?.user,
                        screen = Screen.HOME
                    )
                }
                _events.emit(GameUiEvent.ShowSuccess("Bienvenido, ${authData?.user?.username}!"))
                fetchHostedGames()
            } else {
                _uiState.update {
                    it.copy(isLoading = false, error = result.exceptionOrNull()?.message)
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }
}

fun GameViewModel.login(email: String, pass: String) {
    viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val client = GameClient()
            val result = client.login(ServerConfig.WS_URL, email, pass)

            if (result.isSuccess) {
                val authData = result.getOrNull()
                if (authData != null) {
                    sessionManager?.saveSession(authData.user)
                    authData.token?.let { sessionManager?.saveAuthToken(it) }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        userProfile = authData?.user,
                        screen = Screen.HOME
                    )
                }
                _events.emit(GameUiEvent.ShowSuccess("Hola de nuevo, ${authData?.user?.username}!"))
                fetchHostedGames()
            } else {
                _uiState.update {
                    it.copy(isLoading = false, error = result.exceptionOrNull()?.message)
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }
}

fun GameViewModel.logout() {
    sessionManager?.clearSession()
    _uiState.update { it.copy(userProfile = null) }
}
