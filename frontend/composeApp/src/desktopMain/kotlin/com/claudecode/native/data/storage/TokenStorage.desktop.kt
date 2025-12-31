package com.claudecode.native.data.storage

/**
 * Desktop implementation using in-memory storage.
 * Token is lost when app closes (session-based).
 */
actual object TokenStorage {
    private var token: String? = null

    actual fun saveToken(token: String) {
        this.token = token
    }

    actual fun getToken(): String? {
        return token
    }

    actual fun clearToken() {
        token = null
    }
}
