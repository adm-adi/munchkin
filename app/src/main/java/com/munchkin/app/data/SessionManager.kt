package com.munchkin.app.data

import android.content.Context
import androidx.core.content.edit
import com.munchkin.app.network.UserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Manages user session persistence using SharedPreferences.
 */
class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("munchkin_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val KEY_USER_PROFILE = "user_profile"
    }

    /**
     * Save the user profile to persistent storage.
     */
    fun saveSession(profile: UserProfile) {
        val jsonString = json.encodeToString(profile)
        prefs.edit { 
            putString(KEY_USER_PROFILE, jsonString) 
        }
    }

    /**
     * Retrieve the saved user profile, or null if not found.
     */
    fun getSession(): UserProfile? {
        val jsonString = prefs.getString(KEY_USER_PROFILE, null) ?: return null
        return try {
            json.decodeFromString<UserProfile>(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Clear the user session (Logout).
     */
    fun clearSession() {
        prefs.edit { 
            remove(KEY_USER_PROFILE) 
        }
    }
}
