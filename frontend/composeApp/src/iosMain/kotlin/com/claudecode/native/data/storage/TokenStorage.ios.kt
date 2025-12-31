package com.claudecode.native.data.storage

import platform.Foundation.NSUserDefaults

actual object TokenStorage {
    private const val TOKEN_KEY = "auth_token"
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun saveToken(token: String) {
        defaults.setObject(token, TOKEN_KEY)
    }

    actual fun getToken(): String? {
        return defaults.stringForKey(TOKEN_KEY)
    }

    actual fun clearToken() {
        defaults.removeObjectForKey(TOKEN_KEY)
    }
}
