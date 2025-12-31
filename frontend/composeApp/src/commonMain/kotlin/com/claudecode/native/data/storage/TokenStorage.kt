package com.claudecode.native.data.storage

/**
 * Platform-specific token storage.
 * WASM: Uses localStorage for persistence across page refreshes.
 * Desktop: Uses in-memory storage (session-based).
 */
expect object TokenStorage {
    fun saveToken(token: String)
    fun getToken(): String?
    fun clearToken()
}
