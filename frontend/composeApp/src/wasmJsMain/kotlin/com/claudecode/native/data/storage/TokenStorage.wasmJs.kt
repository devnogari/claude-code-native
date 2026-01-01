package com.claudecode.native.data.storage

import kotlinx.browser.localStorage

/**
 * WASM implementation using browser localStorage.
 * Token and server host persist across page refreshes.
 */
actual object TokenStorage {
    private const val TOKEN_KEY = "auth_token"
    private const val SERVER_HOST_KEY = "server_host"

    actual fun saveToken(token: String) {
        localStorage.setItem(TOKEN_KEY, token)
    }

    actual fun getToken(): String? {
        return localStorage.getItem(TOKEN_KEY)
    }

    actual fun clearToken() {
        localStorage.removeItem(TOKEN_KEY)
    }

    actual fun saveServerHost(host: String) {
        localStorage.setItem(SERVER_HOST_KEY, host)
    }

    actual fun getServerHost(): String? {
        return localStorage.getItem(SERVER_HOST_KEY)
    }

    actual fun clearServerHost() {
        localStorage.removeItem(SERVER_HOST_KEY)
    }
}
