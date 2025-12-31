package com.claudecode.native.data.storage

/**
 * Platform-specific favorite projects storage.
 * WASM: Uses localStorage for persistence across page refreshes.
 * Desktop: Uses file-based storage for persistence across app restarts.
 * Android: Uses SharedPreferences for persistent storage.
 * iOS: Uses NSUserDefaults for persistent storage.
 */
expect object FavoriteStorage {
    fun saveFavorites(favorites: Set<String>)
    fun getFavorites(): Set<String>
    fun clearFavorites()
}
