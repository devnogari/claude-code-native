package com.claudecode.native.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.claudecode.native.data.model.ServerListState
import kotlinx.serialization.json.Json

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

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun getServerList(): ServerListState? {
        return try {
            val content = prefs?.getString(SERVERS_KEY, null)
            if (content != null && content.isNotBlank()) {
                json.decodeFromString<ServerListState>(content)
            } else {
                null
            }
        } catch (e: Exception) {
            println("ServerStorage: Failed to read server list: ${e.message}")
            null
        }
    }

    actual fun saveServerList(state: ServerListState) {
        try {
            val content = json.encodeToString(ServerListState.serializer(), state)
            prefs?.edit()?.putString(SERVERS_KEY, content)?.apply()
        } catch (e: Exception) {
            println("ServerStorage: Failed to save server list: ${e.message}")
        }
    }

    actual fun clear() {
        prefs?.edit()?.remove(SERVERS_KEY)?.apply()
    }
}
