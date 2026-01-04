package com.claudecode.native.data.storage

import com.claudecode.native.data.model.ServerListState

/**
 * Platform-specific server list storage.
 * Stores multiple server configurations with their auth tokens.
 */
expect object ServerStorage {
    /**
     * Gets the saved server list state.
     * @return The server list state, or null if not set
     */
    fun getServerList(): ServerListState?

    /**
     * Saves the server list state.
     * @param state The server list state to save
     */
    fun saveServerList(state: ServerListState)

    /**
     * Clears all server data.
     */
    fun clear()
}
