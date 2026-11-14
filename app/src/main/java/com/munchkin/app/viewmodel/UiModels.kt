package com.munchkin.app.viewmodel

import com.munchkin.app.R
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId
import com.munchkin.app.di.StringResolver
import com.munchkin.app.network.ErrorCode
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.ServerErrorException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * One-shot UI events emitted via [com.munchkin.app.di.GameSessionCoordinator.events].
 */
sealed interface GameUiEvent {
    data class ShowError(val message: String) : GameUiEvent
    data class ShowSuccess(val message: String) : GameUiEvent
    data class ShowMessage(val message: String) : GameUiEvent
    data object Reconnected : GameUiEvent
}

/**
 * Canonical authorization check for who may mutate an active combat.
 * Only the main fighter, the active helper, or the host may touch combat state.
 */
fun canTouchCombat(gameState: GameState?, playerId: PlayerId?): Boolean {
    val combat = gameState?.combat ?: return false
    if (playerId == null) return false
    return combat.mainPlayerId == playerId ||
        combat.helperPlayerId == playerId ||
        gameState.hostId == playerId
}

/**
 * Maps network/server exceptions into localized user-facing error strings without Android Context leaks.
 */
internal fun getFriendlyErrorMessage(error: Throwable?, strings: StringResolver): String {
    if (error is ServerErrorException) {
        return mapErrorCode(error.code, error.message, strings)
    }
    if (error is GameClient.ServerTimeoutException || error is SocketTimeoutException) {
        return strings.get(R.string.error_timeout)
    }
    if (error is UnknownHostException) {
        return strings.get(R.string.error_host_unresolved)
    }
    if (error is ConnectException) {
        return strings.get(R.string.error_server_unavailable)
    }

    val raw = error?.message.orEmpty()
    return mapRawErrorString(raw, strings)
}

internal fun mapServerErrorMessage(raw: String, strings: StringResolver): String {
    return mapRawErrorString(raw, strings)
}

private fun mapErrorCode(code: ErrorCode, rawMessage: String?, strings: StringResolver): String {
    return when (code) {
        ErrorCode.INVALID_JOIN_CODE -> strings.get(R.string.error_invalid_code)
        ErrorCode.GAME_NOT_FOUND -> strings.get(R.string.error_not_found)
        ErrorCode.GAME_FULL -> strings.get(R.string.error_game_full)
        ErrorCode.GAME_ALREADY_STARTED -> strings.get(R.string.error_game_started)
        ErrorCode.PLAYER_NOT_FOUND -> strings.get(R.string.error_player_not_found)
        ErrorCode.UNAUTHORIZED -> strings.get(R.string.error_unauthorized_join)
        ErrorCode.VALIDATION_FAILED, ErrorCode.INVALID_DATA -> strings.get(R.string.error_invalid_data)
        ErrorCode.RATE_LIMITED -> strings.get(R.string.error_rate_limited)
        ErrorCode.FORBIDDEN -> strings.get(R.string.error_forbidden)
        ErrorCode.PERMISSION_DENIED -> strings.get(R.string.error_permission_denied)
        ErrorCode.EMAIL_EXISTS -> strings.get(R.string.error_email_exists)
        ErrorCode.USERNAME_EXISTS -> strings.get(R.string.error_username_exists)
        ErrorCode.UPDATE_FAILED -> strings.get(R.string.error_profile_update)
        ErrorCode.AUTH_FAILED, ErrorCode.LOGIN_ERROR, ErrorCode.REGISTER_FAILED ->
            rawMessage?.takeIf { it.isNotBlank() && !it.startsWith("Auth", ignoreCase = true) }
                ?: strings.get(R.string.error_auth_failed)
        ErrorCode.COMBAT_ALREADY_ACTIVE -> strings.get(R.string.error_combat_active)
        ErrorCode.NO_ACTIVE_COMBAT -> strings.get(R.string.error_no_combat)
        ErrorCode.COMBAT_MONSTER_LIMIT -> strings.get(R.string.error_monster_limit)
        ErrorCode.INVALID_HELPER -> strings.get(R.string.error_invalid_helper)
        ErrorCode.COMBAT_BONUS_LIMIT -> strings.get(R.string.error_bonus_limit)
        ErrorCode.CONNECTION_LOST -> strings.get(R.string.error_connection_lost_retry)
        ErrorCode.ADD_MONSTER_ERROR -> strings.get(R.string.error_save_monster)
        ErrorCode.GAME_FINISHED,
        ErrorCode.CONFLICT,
        ErrorCode.INTERNAL_ERROR,
        ErrorCode.GENERAL_ERROR,
        ErrorCode.SERVER_ERROR,
        ErrorCode.SEARCH_ERROR,
        ErrorCode.UNKNOWN -> rawMessage?.takeIf { it.isNotBlank() }
            ?.let { strings.get(R.string.error_server_generic, it) }
            ?: strings.get(R.string.error_unknown)
    }
}

private fun mapRawErrorString(raw: String, strings: StringResolver): String {
    val lower = raw.lowercase()
    return when {
        lower.contains("timeout") || lower.contains("tiempo") -> strings.get(R.string.error_timeout)
        lower.contains("unknownhost") || lower.contains("unable to resolve host") ->
            strings.get(R.string.error_host_unresolved)
        lower.contains("connection refused") || lower.contains("failed to connect") ->
            strings.get(R.string.error_server_unavailable)
        lower.contains("conexión cerrada") || lower.contains("connection closed") ->
            strings.get(R.string.error_connection_lost_retry)
        lower.contains("invalid_join_code") -> strings.get(R.string.error_invalid_code)
        lower.contains("game_not_found") -> strings.get(R.string.error_not_found)
        lower.contains("game_full") -> strings.get(R.string.error_game_full)
        lower.contains("game_already_started") -> strings.get(R.string.error_game_started)
        lower.contains("rate_limited") -> strings.get(R.string.error_rate_limited)
        lower.contains("forbidden") -> strings.get(R.string.error_forbidden)
        raw.isNotBlank() -> raw
        else -> strings.get(R.string.error_connection_retry)
    }
}
