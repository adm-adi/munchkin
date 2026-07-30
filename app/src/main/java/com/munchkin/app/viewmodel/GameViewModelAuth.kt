package com.munchkin.app.viewmodel

import androidx.lifecycle.viewModelScope
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R
import com.munchkin.app.network.GameClient
import com.munchkin.app.core.Gender
import com.munchkin.app.ui.theme.AvatarResources
import com.munchkin.app.network.ServerConfig
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Creates an account.
 *
 * The avatar defaults to a random slot rather than always 0. Asking for one during
 * sign-up would add a step to the one screen that should stay short, but every
 * account landing on slot 0 made the ranking a wall of identical portraits. It is
 * editable from the profile afterwards.
 */
fun GameViewModel.register(
    username: String,
    email: String,
    pass: String,
    avatarId: Int = (0 until AvatarResources.AVATAR_COUNT).random(),
    gender: Gender = Gender.M
) {
    viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val client = GameClient() // Temp instance
            val result = client.register(ServerConfig.WS_URL, username, email, pass, avatarId, gender)

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
                _events.emit(GameUiEvent.ShowSuccess(
                    MunchkinApp.context.getString(R.string.welcome_user, authData?.user?.username ?: "")
                ))
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
                _events.emit(GameUiEvent.ShowSuccess(
                    MunchkinApp.context.getString(R.string.welcome_back_user, authData?.user?.username ?: "")
                ))
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
