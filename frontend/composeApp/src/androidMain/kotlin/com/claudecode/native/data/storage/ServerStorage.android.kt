package com.claudecode.native.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.claudecode.native.data.model.ServerListState
import kotlinx.serialization.json.Json

private const val TAG = "ServerStorage"

/**
 * Android implementation using SharedPreferences for persistent storage.
 * Server list persists across app restarts.
 */
actual object ServerStorage {
    private const val PREFS_NAME = "claude_code_servers"
    private const val SERVERS_KEY = "server_list"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        // Avoid re-initialization on Android process recreation
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun getServerList(): ServerListState? {
        if (!::prefs.isInitialized) {
            Log.w(TAG, "ServerStorage not initialized")
            return null
        }
        return try {
            val content = prefs.getString(SERVERS_KEY, null)
            if (content != null && content.isNotBlank()) {
                json.decodeFromString<ServerListState>(content)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read server list", e)
            null
        }
    }

    actual fun saveServerList(state: ServerListState) {
        if (!::prefs.isInitialized) {
            Log.w(TAG, "ServerStorage not initialized")
            return
        }
        try {
            val content = json.encodeToString(ServerListState.serializer(), state)
            prefs.edit().putString(SERVERS_KEY, content).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save server list", e)
        }
    }

    actual fun clear() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(SERVERS_KEY).apply()
    }
}
