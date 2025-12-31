package com.claudecode.native.data.storage

import java.io.File

/**
 * Desktop implementation using file-based storage.
 * Favorites persist across app restarts and hot reloads.
 */
actual object FavoriteStorage {
    private val favoritesFile: File by lazy {
        val configDir = File(System.getProperty("user.home"), ".claude-code-native")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        File(configDir, "favorites")
    }

    actual fun saveFavorites(favorites: Set<String>) {
        try {
            favoritesFile.writeText(favorites.joinToString("\n"))
        } catch (e: Exception) {
            // Ignore write errors
        }
    }

    actual fun getFavorites(): Set<String> {
        return try {
            if (favoritesFile.exists()) {
                favoritesFile.readText()
                    .split("\n")
                    .filter { it.isNotBlank() }
                    .toSet()
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            emptySet()
        }
    }

    actual fun clearFavorites() {
        try {
            if (favoritesFile.exists()) {
                favoritesFile.delete()
            }
        } catch (e: Exception) {
            // Ignore delete errors
        }
    }
}
