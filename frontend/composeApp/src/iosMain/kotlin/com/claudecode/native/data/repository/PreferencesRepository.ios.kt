package com.claudecode.native.data.repository

import platform.Foundation.NSUserDefaults

/**
 * iOS implementation using NSUserDefaults.
 */
actual class PreferencesRepository {
    private val userDefaults = NSUserDefaults.standardUserDefaults

    actual fun getString(key: String, defaultValue: String): String {
        return userDefaults.stringForKey(key) ?: defaultValue
    }

    actual fun setString(key: String, value: String) {
        userDefaults.setObject(value, key)
        userDefaults.synchronize()
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        // Check if key exists first
        if (userDefaults.objectForKey(key) == null) {
            return defaultValue
        }
        return userDefaults.boolForKey(key)
    }

    actual fun setBoolean(key: String, value: Boolean) {
        userDefaults.setBool(value, key)
        userDefaults.synchronize()
    }
}
