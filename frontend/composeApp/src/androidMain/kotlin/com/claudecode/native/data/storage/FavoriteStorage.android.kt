package com.claudecode.native.data.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Android implementation using SharedPreferences for persistent storage.
 */
actual object FavoriteStorage {
    private const val PREFS_NAME = "claude_code_prefs"
    private const val FAVORITES_KEY = "favorite_projects"
    private const val SEPARATOR = "|||"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun saveFavorites(favorites: Set<String>) {
        if (favorites.isEmpty()) {
            prefs?.edit()?.remove(FAVORITES_KEY)?.apply()
        } else {
            prefs?.edit()?.putString(FAVORITES_KEY, favorites.joinToString(SEPARATOR))?.apply()
        }
    }

    actual fun getFavorites(): Set<String> {
        val stored = prefs?.getString(FAVORITES_KEY, null) ?: return emptySet()
        return stored.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    actual fun clearFavorites() {
        prefs?.edit()?.remove(FAVORITES_KEY)?.apply()
    }
}
