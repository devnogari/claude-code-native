package com.claudecode.native.data.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Android implementation using SharedPreferences for persistent storage.
 */
actual object TokenStorage {
    private const val PREFS_NAME = "claude_code_prefs"
    private const val TOKEN_KEY = "auth_token"
    private const val SERVER_HOST_KEY = "server_host"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun saveToken(token: String) {
        prefs?.edit()?.putString(TOKEN_KEY, token)?.apply()
    }

    actual fun getToken(): String? {
        return prefs?.getString(TOKEN_KEY, null)
    }

    actual fun clearToken() {
        prefs?.edit()?.remove(TOKEN_KEY)?.apply()
    }

    actual fun saveServerHost(host: String) {
        prefs?.edit()?.putString(SERVER_HOST_KEY, host)?.apply()
    }

    actual fun getServerHost(): String? {
        return prefs?.getString(SERVER_HOST_KEY, null)
    }

    actual fun clearServerHost() {
        prefs?.edit()?.remove(SERVER_HOST_KEY)?.apply()
    }
}
