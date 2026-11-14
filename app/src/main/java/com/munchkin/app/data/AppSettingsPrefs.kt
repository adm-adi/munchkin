package com.munchkin.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists user-configurable application UI preferences backed by SharedPreferences.
 */
class AppSettingsPrefs(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _largeNumbersMode = MutableStateFlow(prefs.getBoolean(KEY_LARGE_NUMBERS_MODE, false))
    val largeNumbersMode: StateFlow<Boolean> = _largeNumbersMode.asStateFlow()

    fun setLargeNumbersMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LARGE_NUMBERS_MODE, enabled).apply()
        _largeNumbersMode.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "munchkin_app_settings"
        private const val KEY_LARGE_NUMBERS_MODE = "large_numbers_mode"
    }
}
