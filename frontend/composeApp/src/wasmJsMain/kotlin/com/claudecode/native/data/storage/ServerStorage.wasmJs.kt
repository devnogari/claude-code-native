package com.claudecode.native.data.storage

import com.claudecode.native.data.model.ServerListState
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json

/**
 * WASM implementation using browser localStorage.
 * Server list persists across page refreshes.
 */
actual object ServerStorage {
    private const val SERVERS_KEY = "servers"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    actual fun getServerList(): ServerListState? {
        return try {
            val content = localStorage.getItem(SERVERS_KEY)
            if (content != null && content.isNotBlank()) {
                json.decodeFromString<ServerListState>(content)
            } else {
                null
            }
        } catch (e: Exception) {
            console.error("ServerStorage: Failed to read servers: ${e.message}")
            null
        }
    }

    actual fun saveServerList(state: ServerListState) {
        try {
            localStorage.setItem(SERVERS_KEY, json.encodeToString(ServerListState.serializer(), state))
        } catch (e: Exception) {
            console.error("ServerStorage: Failed to save servers: ${e.message}")
        }
    }

    actual fun clear() {
        localStorage.removeItem(SERVERS_KEY)
    }
}
