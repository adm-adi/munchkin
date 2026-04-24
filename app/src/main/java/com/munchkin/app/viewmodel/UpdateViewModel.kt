package com.munchkin.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R
import com.munchkin.app.update.UpdateChecker
import com.munchkin.app.update.UpdateInfo
import com.munchkin.app.update.UpdateResult
import com.munchkin.app.update.UpdateService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UpdateViewModel(
    private val updateService: UpdateService = UpdateChecker(MunchkinApp.context),
    private val messages: UpdateMessages = UpdateMessages(
        updateAvailable = { version ->
            MunchkinApp.context.getString(R.string.update_available_message, version)
        },
        upToDate = {
            MunchkinApp.context.getString(R.string.up_to_date)
        },
        checkError = { error ->
            MunchkinApp.context.getString(R.string.update_check_error_format, error)
        }
    ),
    private val logWarning: (String) -> Unit = { message ->
        Log.w("UpdateViewModel", message)
    },
    autoCheck: Boolean = true
) : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    init {
        if (autoCheck) {
            checkForUpdates(showResultMessage = false)
        }
    }

    fun checkForUpdates(showResultMessage: Boolean = true) {
        if (_uiState.value.isCheckingUpdate) return
        _uiState.update { it.copy(isCheckingUpdate = true, message = null) }

        viewModelScope.launch {
            try {
                when (val result = updateService.checkForUpdate()) {
                    is UpdateResult.UpdateAvailable -> {
                        _uiState.update {
                            it.copy(
                                updateInfo = result.info,
                                message = if (showResultMessage) {
                                    messages.updateAvailable(result.info.version)
                                } else {
                                    null
                                }
                            )
                        }
                    }
                    is UpdateResult.NoUpdate -> {
                        _uiState.update {
                            it.copy(
                                updateInfo = null,
                                message = if (showResultMessage) {
                                    messages.upToDate()
                                } else {
                                    null
                                }
                            )
                        }
                    }
                    is UpdateResult.Error -> {
                        logWarning("Update check failed: ${result.message}")
                        if (showResultMessage) {
                            _uiState.update {
                                it.copy(
                                    message = messages.checkError(result.message)
                                )
                            }
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(isCheckingUpdate = false) }
            }
        }
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(updateInfo = null) }
    }

    fun downloadUpdate() {
        val info = _uiState.value.updateInfo ?: return
        _uiState.update { it.copy(isDownloading = true) }

        updateService.downloadAndInstall(
            updateInfo = info,
            onComplete = {
                _uiState.update { it.copy(isDownloading = false) }
            }
        )
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}

data class UpdateUiState(
    val updateInfo: UpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isDownloading: Boolean = false,
    val message: String? = null
)

data class UpdateMessages(
    val updateAvailable: (String) -> String,
    val upToDate: () -> String,
    val checkError: (String) -> String
)
