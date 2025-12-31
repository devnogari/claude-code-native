package com.claudecode.native.data.storage

import java.io.File

/**
 * Desktop implementation using file-based storage.
 * Token persists across app restarts and hot reloads.
 */
actual object TokenStorage {
    private val tokenFile: File by lazy {
        val configDir = File(System.getProperty("user.home"), ".claude-code-native")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        File(configDir, "auth_token")
    }

    actual fun saveToken(token: String) {
        try {
            tokenFile.writeText(token)
        } catch (e: Exception) {
            // Ignore write errors
        }
    }

    actual fun getToken(): String? {
        return try {
            if (tokenFile.exists()) {
                tokenFile.readText().takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    actual fun clearToken() {
        try {
            if (tokenFile.exists()) {
                tokenFile.delete()
            }
        } catch (e: Exception) {
            // Ignore delete errors
        }
    }
}
