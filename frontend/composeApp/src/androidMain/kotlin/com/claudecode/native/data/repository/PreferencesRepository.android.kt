package com.claudecode.native.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * Android implementation using SharedPreferences.
 * Note: This requires context to be set via init() before use.
 */
actual class PreferencesRepository {
    private val prefs: SharedPreferences
        get() = sharedPrefs ?: throw IllegalStateException("PreferencesRepository not initialized. Call init(context) first.")

    actual fun getString(key: String, defaultValue: String): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    actual fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    actual fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        private var sharedPrefs: SharedPreferences? = null

        /**
         * Initialize with Android context. Must be called before using PreferencesRepository.
         */
        fun init(context: Context) {
            sharedPrefs = context.getSharedPreferences("claude_code_native_prefs", Context.MODE_PRIVATE)
        }
    }
}
