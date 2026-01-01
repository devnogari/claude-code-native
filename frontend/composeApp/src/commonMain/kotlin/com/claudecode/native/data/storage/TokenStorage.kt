package com.claudecode.native.data.storage

/**
 * Platform-specific token storage.
 * WASM: Uses localStorage for persistence across page refreshes.
 * Desktop: Uses file-based storage for persistence across app restarts.
 */
expect object TokenStorage {
    fun saveToken(token: String)
    fun getToken(): String?
    fun clearToken()

    /**
     * Saves the server host/IP address.
     * @param host The server host (e.g., "localhost:8080" or "192.168.1.100:8080")
     */
    fun saveServerHost(host: String)

    /**
     * Gets the saved server host/IP address.
     * @return The server host, or null if not set
     */
    fun getServerHost(): String?

    /**
     * Clears the saved server host.
     */
    fun clearServerHost()
}
