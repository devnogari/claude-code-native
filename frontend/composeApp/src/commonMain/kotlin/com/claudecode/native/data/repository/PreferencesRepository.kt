package com.claudecode.native.data.repository

/**
 * Preference keys used across all platforms.
 */
object PreferenceKeys {
    const val DARK_MODE = "dark_mode"
    const val SERVER_URL = "server_url"
    /** Whether to use bypass permissions mode by default for new conversations */
    const val BYPASS_DEFAULT = "bypass_default"
}

/**
 * Platform-agnostic preferences repository for storing app settings.
 * Each platform provides its own implementation.
 */
expect class PreferencesRepository() {
    /**
     * Gets a string preference value.
     */
    fun getString(key: String, defaultValue: String): String

    /**
     * Sets a string preference value.
     */
    fun setString(key: String, value: String)

    /**
     * Gets a boolean preference value.
     */
    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    /**
     * Sets a boolean preference value.
     */
    fun setBoolean(key: String, value: Boolean)
}
