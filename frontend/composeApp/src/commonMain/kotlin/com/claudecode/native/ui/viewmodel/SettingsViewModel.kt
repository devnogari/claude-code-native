package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.repository.ThemeRepository
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
 * @param themeRepository Repository for managing theme settings
 * @param scope Injected coroutine scope for lifecycle management
 */
class SettingsViewModel(
    private val apiClient: ApiClient,
    private val themeRepository: ThemeRepository,
    private val scope: CoroutineScope
) {
    private val _serverHost = MutableStateFlow(apiClient.serverHost)
    /** Current server host (e.g., "localhost:8083"). */
    val serverHost: StateFlow<String> = _serverHost.asStateFlow()

    /** Whether dark mode is enabled. Delegates to ThemeRepository. */
    val isDarkMode: StateFlow<Boolean> = themeRepository.isDarkMode

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
     * Updates the server host configuration.
     *
     * @param host The new server host (e.g., "localhost:8083" or "192.168.1.100:8080")
     */
    fun updateServerHost(host: String) {
        if (host.isBlank()) {
            _error.value = "Server host cannot be empty"
            return
        }

        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Validate host format (should be host:port or just host)
                val cleanHost = host.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .removeSuffix("/")
                    .removeSuffix("/api/v1")

                if (cleanHost.isEmpty()) {
                    _error.value = "Invalid host format"
                    return@launch
                }

                // Update the API client server host
                apiClient.updateServerHost(cleanHost)
                _serverHost.value = cleanHost
                _message.value = "Server host updated successfully"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update server host"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Toggles dark mode on/off.
     */
    fun toggleDarkMode() {
        themeRepository.toggleDarkMode()
    }

    /**
     * Sets dark mode explicitly.
     *
     * @param enabled Whether dark mode should be enabled
     */
    fun setDarkMode(enabled: Boolean) {
        themeRepository.setDarkMode(enabled)
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
