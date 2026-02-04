package com.munchkin.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Manages app locale settings with per-app language support (Android 13+).
 * Falls back to AppCompat for older versions.
 */
object LocaleManager {
    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LOCALE = "app_locale"
    
    enum class AppLocale(val code: String, val displayName: String) {
        SYSTEM("", "Sistema"),
        SPANISH("es", "Español"),
        ENGLISH("en", "English"),
        FRENCH("fr", "Français")
    }
    
    fun getAvailableLocales(): List<AppLocale> = AppLocale.entries
    
    fun getCurrentLocale(context: Context): AppLocale {
        val prefs = getPrefs(context)
        val code = prefs.getString(KEY_LOCALE, "") ?: ""
        return AppLocale.entries.find { it.code == code } ?: AppLocale.SYSTEM
    }
    
    fun setLocale(context: Context, locale: AppLocale) {
        // Save preference
        getPrefs(context).edit().putString(KEY_LOCALE, locale.code).apply()
        
        // Apply locale
        val localeList = if (locale == AppLocale.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(locale.code)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }
    
    fun applyStoredLocale(context: Context) {
        val locale = getCurrentLocale(context)
        if (locale != AppLocale.SYSTEM) {
            val localeList = LocaleListCompat.forLanguageTags(locale.code)
            AppCompatDelegate.setApplicationLocales(localeList)
        }
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
