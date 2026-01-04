package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.model.ServerConfig
import com.claudecode.native.data.model.ServerListState
import com.claudecode.native.data.repository.ServerRepository
import com.claudecode.native.data.websocket.UnifiedWebSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing server configurations and switching between servers.
 */
class ServerViewModel(
    private val serverRepository: ServerRepository,
    private val apiClient: ApiClient,
    private val webSocketClient: UnifiedWebSocketClient,
    private val scope: CoroutineScope
) {
    /** Current server list state. */
    val state: StateFlow<ServerListState> = serverRepository.state

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _serverSwitched = MutableStateFlow(false)
    /** True when a server switch has occurred and the app should refresh. */
    val serverSwitched: StateFlow<Boolean> = _serverSwitched.asStateFlow()

    /** List of all configured servers. */
    val servers: List<ServerConfig>
        get() = serverRepository.servers

    /** Currently active server. */
    val currentServer: ServerConfig?
        get() = serverRepository.currentServer

    /** Whether there are any servers configured. */
    val hasServers: Boolean
        get() = serverRepository.servers.isNotEmpty()

    /**
     * Adds a new server configuration.
     */
    fun addServer(name: String, host: String, description: String = "") {
        if (name.isBlank()) {
            _error.value = "Server name cannot be empty"
            return
        }
        if (host.isBlank()) {
            _error.value = "Server host cannot be empty"
            return
        }

        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val server = serverRepository.addServer(name, host, description)
                _message.value = "Server '${server.name}' added successfully"

                // If this is the first server, also switch to it
                if (serverRepository.servers.size == 1) {
                    applyServerToClients(server)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to add server"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Updates an existing server configuration.
     */
    fun updateServer(id: String, name: String, host: String, description: String) {
        if (name.isBlank()) {
            _error.value = "Server name cannot be empty"
            return
        }
        if (host.isBlank()) {
            _error.value = "Server host cannot be empty"
            return
        }

        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                serverRepository.updateServer(id, name, host, description)
                _message.value = "Server updated successfully"

                // If this is the current server, update the clients
                if (serverRepository.currentServerId == id) {
                    val server = serverRepository.currentServer
                    if (server != null) {
                        applyServerToClients(server)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update server"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Deletes a server configuration.
     */
    fun deleteServer(id: String) {
        val server = serverRepository.servers.find { it.id == id }
        val wasCurrentServer = serverRepository.currentServerId == id

        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                serverRepository.deleteServer(id)
                _message.value = "Server '${server?.name}' deleted"

                // If deleted server was current, switch to new current
                if (wasCurrentServer) {
                    val newCurrent = serverRepository.currentServer
                    if (newCurrent != null) {
                        applyServerToClients(newCurrent)
                        _serverSwitched.value = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to delete server"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Switches to a different server.
     * Updates ApiClient and disconnects WebSocket. WebSocket reconnection happens
     * when user enters a conversation (requires conversationId). The [serverSwitched]
     * flag signals the app to refresh and clear stale conversation state.
     */
    fun switchToServer(id: String) {
        if (serverRepository.currentServerId == id) {
            return // Already on this server
        }

        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Disconnect current WebSocket
                webSocketClient.disconnect()

                // Switch server in repository
                serverRepository.switchServer(id)

                // Apply new server to clients
                val server = serverRepository.currentServer
                if (server != null) {
                    applyServerToClients(server)
                    _message.value = "Switched to '${server.name}'"
                    _serverSwitched.value = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to switch server"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Applies the server configuration to API and WebSocket clients.
     * Note: UnifiedWebSocketClient reads server host from TokenStorage dynamically
     * via its baseUrl getter, so updating ApiClient (which updates TokenStorage)
     * is sufficient. The WebSocket will use the new host on next connection.
     */
    private suspend fun applyServerToClients(server: ServerConfig) {
        // Update API client (also updates TokenStorage, which WebSocket reads from)
        apiClient.updateServerHost(server.host)

        // Set auth token if available
        if (server.authToken != null) {
            apiClient.setAuthToken(server.authToken)
        } else {
            apiClient.clearAuthToken()
        }
    }

    /**
     * Saves the auth token for the current server after login.
     */
    fun saveCurrentToken(token: String) {
        val currentId = serverRepository.currentServerId ?: return
        serverRepository.updateToken(currentId, token)
    }

    /**
     * Clears the auth token for the current server (logout).
     */
    fun clearCurrentToken() {
        serverRepository.clearCurrentToken()
    }

    /**
     * Gets the auth token for the current server.
     */
    fun getCurrentToken(): String? {
        return serverRepository.getCurrentToken()
    }

    /**
     * Initializes the clients with the current server configuration.
     * Should be called on app startup.
     *
     * Note: This is a suspend function that waits for initialization to complete.
     * This ensures the token is set in ApiClient before any API calls are made.
     */
    suspend fun initializeWithCurrentServer() {
        val server = serverRepository.currentServer ?: return
        applyServerToClients(server)
    }

    /**
     * Resets the server switched flag.
     */
    fun resetServerSwitchedState() {
        _serverSwitched.value = false
    }

    fun setError(message: String) {
        _error.value = message
    }

    /**
     * Atomically sets the error message only if there isn't one already set.
     * Uses compareAndSet to prevent race conditions when multiple coroutines
     * might try to set an error concurrently.
     */
    fun setErrorIfNull(message: String) {
        _error.compareAndSet(null, message)
    }

    fun clearError() {
        _error.value = null
    }

    fun clearMessage() {
        _message.value = null
    }
}
