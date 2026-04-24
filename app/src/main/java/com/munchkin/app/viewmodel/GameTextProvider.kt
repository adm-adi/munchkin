package com.munchkin.app.viewmodel

import androidx.annotation.StringRes
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R

interface GameTextProvider {
    fun get(@StringRes resId: Int, vararg args: Any): String
}

object AndroidGameTextProvider : GameTextProvider {
    override fun get(@StringRes resId: Int, vararg args: Any): String {
        return MunchkinApp.context.getString(resId, *args)
    }
}

class FriendlyErrorMapper(
    private val textProvider: GameTextProvider
) {
    fun map(error: Throwable?): String {
        val message = error?.message?.lowercase() ?: ""
        return when {
            "timeout" in message -> textProvider.get(R.string.error_connection_timeout)
            "refused" in message -> textProvider.get(R.string.error_server_unavailable)
            "host" in message && "resolve" in message -> textProvider.get(R.string.error_server_not_found)
            "closed" in message || "reset" in message -> textProvider.get(R.string.error_connection_lost_retry)
            "unauthorized" in message -> textProvider.get(R.string.error_unauthorized_game)
            "not found" in message || "404" in message -> textProvider.get(R.string.error_game_not_found)
            "full" in message -> textProvider.get(R.string.error_game_full_short)
            else -> textProvider.get(R.string.error_connection_retry)
        }
    }
}
