package com.claudecode.native.data.storage

import platform.Foundation.NSUserDefaults

actual object TokenStorage {
    private const val TOKEN_KEY = "auth_token"
    private const val SERVER_HOST_KEY = "server_host"
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

    actual fun saveServerHost(host: String) {
        defaults.setObject(host, SERVER_HOST_KEY)
    }

    actual fun getServerHost(): String? {
        return defaults.stringForKey(SERVER_HOST_KEY)
    }

    actual fun clearServerHost() {
        defaults.removeObjectForKey(SERVER_HOST_KEY)
    }
}
