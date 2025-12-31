package com.claudecode.native.data.repository

import kotlinx.browser.localStorage

/**
 * WASM/JS implementation using browser localStorage.
 */
actual class PreferencesRepository {
    actual fun getString(key: String, defaultValue: String): String {
        return localStorage.getItem(key) ?: defaultValue
    }

    actual fun setString(key: String, value: String) {
        localStorage.setItem(key, value)
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val value = localStorage.getItem(key) ?: return defaultValue
        return value == "true"
    }

    actual fun setBoolean(key: String, value: Boolean) {
        localStorage.setItem(key, value.toString())
    }
}
