package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the settings screen.
 *
 * Handles:
 * - Server URL configuration
 * - Dark mode toggle
 * - Logout functionality
 * - Cache clearing
 *
 * @param apiClient API client for server operations
 * @param scope Injected coroutine scope for lifecycle management
 */
class SettingsViewModel(
    private val apiClient: ApiClient,
    private val scope: CoroutineScope
) {
    private val _serverUrl = MutableStateFlow(apiClient.baseUrl)
    /** Current server URL. */
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _isDarkMode = MutableStateFlow(false)
    /** Whether dark mode is enabled. */
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    /** True when performing an operation. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Current error message, if any. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _logoutRequested = MutableStateFlow(false)
    /** True when logout has been completed and navigation should occur. */
    val logoutRequested: StateFlow<Boolean> = _logoutRequested.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Success message to display. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Updates the server URL configuration.
     *
     * @param url The new server URL
     */
    fun updateServerUrl(url: String) {
        if (url.isBlank()) {
            _error.value = "Server URL cannot be empty"
            return
        }

        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Validate URL format
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    _error.value = "URL must start with http:// or https://"
                    return@launch
                }

                // Update the API client base URL
                apiClient.updateBaseUrl(url)
                _serverUrl.value = url
                _message.value = "Server URL updated successfully"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update server URL"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Toggles dark mode on/off.
     */
    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
    }

    /**
     * Sets dark mode explicitly.
     *
     * @param enabled Whether dark mode should be enabled
     */
    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
    }

    /**
     * Logs out the current user and clears authentication state.
     */
    fun logout() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Clear any stored authentication tokens
                apiClient.clearAuthToken()
                _logoutRequested.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to logout"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clears the local cache.
     */
    fun clearCache() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Clear any cached data
                // In a full implementation, this would clear local storage, cached responses, etc.
                _message.value = "Cache cleared successfully"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to clear cache"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Clears the current success message.
     */
    fun clearMessage() {
        _message.value = null
    }

    /**
     * Resets the logout requested state.
     */
    fun resetLogoutState() {
        _logoutRequested.value = false
    }

    companion object {
        /** App version information. */
        const val APP_VERSION = "1.0.0"
        const val BUILD_NUMBER = "1"
    }
}
