package com.claudecode.native.data.storage

import com.claudecode.native.data.model.ServerListState
import com.claudecode.native.util.DebugLogger
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "ServerStorage"

/**
 * Desktop implementation using file-based storage.
 * Server list persists across app restarts.
 */
actual object ServerStorage {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val configDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".claude-code-native")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val serversFile: File by lazy {
        File(configDir, "servers.json")
    }

    actual fun getServerList(): ServerListState? {
        return try {
            if (serversFile.exists()) {
                val content = serversFile.readText()
                if (content.isNotBlank()) {
                    json.decodeFromString<ServerListState>(content)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to read servers.json: ${e.message}")
            null
        }
    }

    actual fun saveServerList(state: ServerListState) {
        try {
            serversFile.writeText(json.encodeToString(ServerListState.serializer(), state))
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to save servers.json: ${e.message}")
        }
    }

    actual fun clear() {
        try {
            if (serversFile.exists()) serversFile.delete()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to delete servers.json: ${e.message}")
        }
    }
}
