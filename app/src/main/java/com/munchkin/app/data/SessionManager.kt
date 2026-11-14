package com.munchkin.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.munchkin.app.network.UserProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore

/**
 * Manages user session persistence using EncryptedSharedPreferences.
 * Tokens and profile data are stored encrypted on-device (AES256-GCM).
 */
class SessionManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = createPrefs(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_NAME = "munchkin_session"
        private const val KEY_USER_PROFILE = "user_profile"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_PLAYER_ID_PREFIX = "player_id_"
        private const val KEY_RECONNECT_TOKEN_PREFIX = "reconnect_token_"
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unreadable; deleting and recreating", e)
            context.deleteSharedPreferences(PREFS_NAME)
            try {
                buildEncryptedPrefs(context)
            } catch (retryError: Exception) {
                Log.w(TAG, "Deleting corrupted KeyStore master key and retrying", retryError)
                try {
                    val keyStore = KeyStore.getInstance("AndroidKeyStore")
                    keyStore.load(null)
                    keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                } catch (ksError: Exception) {
                    Log.e(TAG, "Failed to delete corrupted master key", ksError)
                }
                context.deleteSharedPreferences(PREFS_NAME)
                buildEncryptedPrefs(context)
            }
        }
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
            Log.e(TAG, "Failed to decode saved user profile", e)
            null
        }
    }

    /**
     * Clear the user session (Logout), including all per-room player IDs and reconnect tokens.
     */
    fun clearSession() {
        val roomKeys = prefs.all.keys.filter { key ->
            key.startsWith(KEY_PLAYER_ID_PREFIX) || key.startsWith(KEY_RECONNECT_TOKEN_PREFIX)
        }
        prefs.edit {
            remove(KEY_USER_PROFILE)
            remove(KEY_AUTH_TOKEN)
            roomKeys.forEach { remove(it) }
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

    fun saveReconnectToken(joinCode: String, token: String) {
        prefs.edit {
            putString(KEY_RECONNECT_TOKEN_PREFIX + joinCode.uppercase(), token)
        }
    }

    fun getReconnectToken(joinCode: String): String? {
        return prefs.getString(KEY_RECONNECT_TOKEN_PREFIX + joinCode.uppercase(), null)
    }
}
