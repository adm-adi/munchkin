package com.munchkin.app.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.munchkin.app.R
import java.util.Locale

/**
 * Manages app locale settings with per-app language support.
 * Uses Android 13+ per-app language API when available.
 */
object LocaleManager {
    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LOCALE = "app_locale"
    
    enum class AppLocale(
        val code: String,
        val displayName: String,
        @StringRes val labelRes: Int
    ) {
        SYSTEM("", "System", R.string.language_system),
        SPANISH("es", "Español", R.string.language_spanish),
        ENGLISH("en", "English", R.string.language_english),
        FRENCH("fr", "Français", R.string.language_french)
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
        
        // Apply locale using Android 13+ API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                if (locale == AppLocale.SYSTEM) {
                    localeManager?.applicationLocales = LocaleList.getEmptyLocaleList()
                } else {
                    localeManager?.applicationLocales = LocaleList.forLanguageTags(locale.code)
                }
            } catch (e: Exception) {
                // Fallback: use AppCompat approach
                applyLocaleWithAppCompat(locale)
            }
        } else {
            // For older Android versions
            applyLocaleWithAppCompat(locale)
        }
    }
    
    private fun applyLocaleWithAppCompat(locale: AppLocale) {
        val localeList = if (locale == AppLocale.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(locale.code)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }
    
    /**
     * Get the locale-wrapped context for use in Activities.
     * Call this in attachBaseContext() for older Android versions.
     */
    fun wrapContext(context: Context): Context {
        val locale = getCurrentLocale(context)
        if (locale == AppLocale.SYSTEM || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context
        }
        
        // Apply locale to configuration for older Android versions
        val newLocale = Locale(locale.code)
        Locale.setDefault(newLocale)
        
        val config = context.resources.configuration
        config.setLocale(newLocale)
        config.setLocales(LocaleList(newLocale))
        
        return context.createConfigurationContext(config)
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
