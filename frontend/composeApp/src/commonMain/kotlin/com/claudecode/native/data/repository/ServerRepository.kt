package com.claudecode.native.data.repository

import com.claudecode.native.data.model.ServerConfig
import com.claudecode.native.data.model.ServerListState
import com.claudecode.native.data.storage.ServerStorage
import com.claudecode.native.data.storage.TokenStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Repository for managing server configurations.
 * Handles CRUD operations, server switching, and migration from old storage.
 */
@OptIn(ExperimentalUuidApi::class)
class ServerRepository {
    private val _state = MutableStateFlow(loadOrMigrate())
    val state: StateFlow<ServerListState> = _state.asStateFlow()

    val servers: List<ServerConfig>
        get() = _state.value.servers

    val currentServer: ServerConfig?
        get() = _state.value.currentServer

    val currentServerId: String?
        get() = _state.value.currentServerId

    /**
     * Loads server list from storage, or migrates from old TokenStorage if needed.
     */
    private fun loadOrMigrate(): ServerListState {
        // Try to load from new storage
        val existing = ServerStorage.getServerList()
        if (existing != null && existing.servers.isNotEmpty()) {
            return existing
        }

        // Migrate from old TokenStorage
        val oldHost = TokenStorage.getServerHost()
        val oldToken = TokenStorage.getToken()

        return if (oldHost != null) {
            val server = ServerConfig(
                id = Uuid.random().toString(),
                name = "Default Server",
                host = oldHost,
                description = "Migrated from previous configuration",
                lastConnectedAt = Clock.System.now().toEpochMilliseconds(),
                authToken = oldToken
            )
            val state = ServerListState(
                servers = listOf(server),
                currentServerId = server.id
            )
            ServerStorage.saveServerList(state)
            state
        } else {
            ServerListState()
        }
    }

    /**
     * Adds a new server configuration.
     * @return The created ServerConfig
     */
    fun addServer(name: String, host: String, description: String = ""): ServerConfig {
        val cleanHost = cleanHostInput(host)
        val server = ServerConfig(
            id = Uuid.random().toString(),
            name = name.trim(),
            host = cleanHost,
            description = description.trim()
        )

        _state.update { current ->
            current.copy(
                servers = current.servers + server,
                currentServerId = current.currentServerId ?: server.id
            )
        }
        persistState()
        return server
    }

    /**
     * Updates an existing server configuration.
     */
    fun updateServer(id: String, name: String, host: String, description: String) {
        val cleanHost = cleanHostInput(host)
        _state.update { current ->
            current.copy(
                servers = current.servers.map { s ->
                    if (s.id == id) s.copy(name = name.trim(), host = cleanHost, description = description.trim())
                    else s
                }
            )
        }
        persistState()
    }

    /**
     * Deletes a server configuration.
     * If the deleted server was current, switches to another server or clears current.
     */
    fun deleteServer(id: String) {
        _state.update { current ->
            val newServers = current.servers.filter { it.id != id }
            val newCurrentId = if (current.currentServerId == id) {
                // Switch to the most recently used of the remaining servers
                newServers.sortedByDescending { it.lastConnectedAt ?: 0L }.firstOrNull()?.id
            } else {
                current.currentServerId
            }
            ServerListState(
                servers = newServers,
                currentServerId = newCurrentId
            )
        }
        persistState()
    }

    /**
     * Switches to a different server.
     * Updates lastConnectedAt timestamp.
     */
    fun switchServer(id: String) {
        _state.update { current ->
            if (current.servers.none { it.id == id }) return@update current
            ServerListState(
                servers = current.servers.map { s ->
                    if (s.id == id) s.copy(lastConnectedAt = Clock.System.now().toEpochMilliseconds()) else s
                },
                currentServerId = id
            )
        }
        persistState()
    }

    /**
     * Updates the auth token for a specific server.
     */
    fun updateToken(serverId: String, token: String?) {
        _state.update { current ->
            current.copy(
                servers = current.servers.map { s ->
                    if (s.id == serverId) s.copy(
                        authToken = token,
                        lastConnectedAt = if (token != null) Clock.System.now().toEpochMilliseconds() else s.lastConnectedAt
                    ) else s
                }
            )
        }
        persistState()
    }

    /**
     * Clears the auth token for the current server (logout).
     */
    fun clearCurrentToken() {
        val currentId = _state.value.currentServerId ?: return
        updateToken(currentId, null)
    }

    /**
     * Gets the auth token for the current server.
     */
    fun getCurrentToken(): String? {
        return currentServer?.authToken
    }

    /**
     * Persists current state to storage.
     * Called after state updates to avoid I/O inside update blocks.
     */
    private fun persistState() {
        ServerStorage.saveServerList(_state.value)
    }

    /**
     * Cleans up the host input by removing protocol prefixes and API paths.
     */
    private fun cleanHostInput(host: String): String {
        return host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removeSuffix("/")
            .removeSuffix("/api/v1")
    }
}
