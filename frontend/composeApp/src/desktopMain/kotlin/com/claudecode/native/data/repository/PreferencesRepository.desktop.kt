package com.claudecode.native.data.repository

import java.util.prefs.Preferences

/**
 * Desktop implementation using Java Preferences API.
 */
actual class PreferencesRepository {
    private val prefs: Preferences = Preferences.userNodeForPackage(PreferencesRepository::class.java)

    actual fun getString(key: String, defaultValue: String): String {
        return prefs.get(key, defaultValue)
    }

    actual fun setString(key: String, value: String) {
        prefs.put(key, value)
        prefs.flush()
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    actual fun setBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
        prefs.flush()
    }
}
