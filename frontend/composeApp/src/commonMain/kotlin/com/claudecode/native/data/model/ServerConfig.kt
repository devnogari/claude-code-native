package com.claudecode.native.data.model

import kotlinx.serialization.Serializable

/**
 * Configuration for a single server connection.
 */
@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val description: String = "",
    val lastConnectedAt: Long? = null,
    val authToken: String? = null
)

/**
 * State containing all servers and the currently selected server.
 */
@Serializable
data class ServerListState(
    val servers: List<ServerConfig> = emptyList(),
    val currentServerId: String? = null
) {
    val currentServer: ServerConfig?
        get() = servers.find { it.id == currentServerId }
}
