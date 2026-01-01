package com.claudecode.native.data.storage

import java.io.File

/**
 * Desktop implementation using file-based storage.
 * Token and server host persist across app restarts and hot reloads.
 */
actual object TokenStorage {
    private val configDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".claude-code-native")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val tokenFile: File by lazy {
        File(configDir, "auth_token")
    }

    private val serverHostFile: File by lazy {
        File(configDir, "server_host")
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

    actual fun saveServerHost(host: String) {
        try {
            serverHostFile.writeText(host)
        } catch (e: Exception) {
            // Ignore write errors
        }
    }

    actual fun getServerHost(): String? {
        return try {
            if (serverHostFile.exists()) {
                serverHostFile.readText().takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    actual fun clearServerHost() {
        try {
            if (serverHostFile.exists()) {
                serverHostFile.delete()
            }
        } catch (e: Exception) {
            // Ignore delete errors
        }
    }
}
