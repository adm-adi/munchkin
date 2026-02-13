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
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_PLAYER_ID_PREFIX = "player_id_"
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
     * Save the auth token.
     */
    fun saveAuthToken(token: String) {
        prefs.edit {
            putString(KEY_AUTH_TOKEN, token)
        }
    }

    /**
     * Get the auth token.
     */
    fun getAuthToken(): String? {
        return prefs.getString(KEY_AUTH_TOKEN, null)
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
            remove(KEY_AUTH_TOKEN)
        }
    }
    
    /**
     * Save the playerId for a specific joinCode (for reconnection).
     */
    fun savePlayerId(joinCode: String, playerId: String) {
        prefs.edit {
            putString(KEY_PLAYER_ID_PREFIX + joinCode.uppercase(), playerId)
        }
    }
    
    /**
     * Get the saved playerId for a joinCode, or null if not found.
     */
    fun getPlayerId(joinCode: String): String? {
        return prefs.getString(KEY_PLAYER_ID_PREFIX + joinCode.uppercase(), null)
    }
    
    /**
     * Clear the playerId for a specific joinCode.
     */
    fun clearPlayerId(joinCode: String) {
        prefs.edit {
            remove(KEY_PLAYER_ID_PREFIX + joinCode.uppercase())
        }
    }
}
