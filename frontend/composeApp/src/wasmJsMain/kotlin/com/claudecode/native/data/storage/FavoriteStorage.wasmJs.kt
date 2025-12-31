package com.claudecode.native.data.storage

import kotlinx.browser.localStorage

/**
 * WASM implementation using browser localStorage.
 * Favorites persist across page refreshes.
 */
actual object FavoriteStorage {
    private const val FAVORITES_KEY = "favorite_projects"
    private const val SEPARATOR = "|||"

    actual fun saveFavorites(favorites: Set<String>) {
        if (favorites.isEmpty()) {
            localStorage.removeItem(FAVORITES_KEY)
        } else {
            localStorage.setItem(FAVORITES_KEY, favorites.joinToString(SEPARATOR))
        }
    }

    actual fun getFavorites(): Set<String> {
        val stored = localStorage.getItem(FAVORITES_KEY) ?: return emptySet()
        return stored.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    actual fun clearFavorites() {
        localStorage.removeItem(FAVORITES_KEY)
    }
}
