package com.claudecode.native.data.storage

import com.claudecode.native.data.model.ServerListState
import kotlinx.serialization.json.Json
import platform.Foundation.NSLog
import platform.Foundation.NSUserDefaults

/**
 * iOS implementation using NSUserDefaults.
 * Server list persists across app restarts.
 */
actual object ServerStorage {
    private const val SERVERS_KEY = "server_list"
    private val defaults = NSUserDefaults.standardUserDefaults
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    actual fun getServerList(): ServerListState? {
        return try {
            val content = defaults.stringForKey(SERVERS_KEY)
            if (content != null && content.isNotBlank()) {
                json.decodeFromString<ServerListState>(content)
            } else {
                null
            }
        } catch (e: Exception) {
            NSLog("ServerStorage: Failed to read server list: %@", e.message ?: "unknown error")
            null
        }
    }

    actual fun saveServerList(state: ServerListState) {
        try {
            val content = json.encodeToString(ServerListState.serializer(), state)
            defaults.setObject(content, SERVERS_KEY)
        } catch (e: Exception) {
            NSLog("ServerStorage: Failed to save server list: %@", e.message ?: "unknown error")
            throw IllegalStateException("Failed to save server list due to serialization error", e)
        }
    }

    actual fun clear() {
        defaults.removeObjectForKey(SERVERS_KEY)
    }
}
