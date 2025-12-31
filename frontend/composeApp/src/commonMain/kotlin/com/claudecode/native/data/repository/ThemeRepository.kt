package com.claudecode.native.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for managing theme settings globally.
 * This allows dark mode state to be shared between SettingsViewModel and AppTheme.
 * Settings are persisted using PreferencesRepository.
 */
class ThemeRepository(
    private val preferencesRepository: PreferencesRepository
) {
    private val _isDarkMode = MutableStateFlow(loadDarkMode())

    /** Whether dark mode is enabled. */
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    /**
     * Loads the dark mode setting from preferences.
     */
    private fun loadDarkMode(): Boolean {
        return preferencesRepository.getBoolean(PreferenceKeys.DARK_MODE, false)
    }

    /**
     * Sets dark mode explicitly and persists the setting.
     *
     * @param enabled Whether dark mode should be enabled
     */
    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        preferencesRepository.setBoolean(PreferenceKeys.DARK_MODE, enabled)
    }

    /**
     * Toggles dark mode on/off and persists the setting.
     */
    fun toggleDarkMode() {
        setDarkMode(!_isDarkMode.value)
    }
}
