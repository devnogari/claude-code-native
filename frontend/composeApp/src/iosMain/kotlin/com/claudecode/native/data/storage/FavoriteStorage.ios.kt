package com.claudecode.native.data.storage

import platform.Foundation.NSUserDefaults

/**
 * iOS implementation using NSUserDefaults for persistent storage.
 */
actual object FavoriteStorage {
    private const val FAVORITES_KEY = "favorite_projects"
    private const val SEPARATOR = "|||"
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun saveFavorites(favorites: Set<String>) {
        if (favorites.isEmpty()) {
            defaults.removeObjectForKey(FAVORITES_KEY)
        } else {
            defaults.setObject(favorites.joinToString(SEPARATOR), FAVORITES_KEY)
        }
    }

    actual fun getFavorites(): Set<String> {
        val stored = defaults.stringForKey(FAVORITES_KEY) ?: return emptySet()
        return stored.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    actual fun clearFavorites() {
        defaults.removeObjectForKey(FAVORITES_KEY)
    }
}
