package com.munchkin.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists whether the first-launch onboarding tutorial has been shown.
 */
class TutorialPrefs(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun isShown(): Boolean = prefs.getBoolean(KEY_SHOWN, false)

    fun markShown() {
        prefs.edit().putBoolean(KEY_SHOWN, true).apply()
    }

    fun reset() {
        prefs.edit().putBoolean(KEY_SHOWN, false).apply()
    }

    companion object {
        private const val PREFS_NAME = "munchkin_tutorial"
        private const val KEY_SHOWN = "tutorial_shown"
    }
}
