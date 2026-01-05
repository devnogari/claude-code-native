package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.websocket.ConnectionState
import com.claudecode.native.data.websocket.UnifiedWebSocketClient
import com.claudecode.native.util.DebugLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Manages WebSocket connection lifecycle and subscription operations.
 *
 * This class is responsible for:
 * - WebSocket connection establishment and maintenance
 * - Conversation subscription/unsubscription
 * - History watch subscription for real-time file changes
 * - Connection retry logic
 *
 * Thread Safety:
 * - Delegates to UnifiedWebSocketClient which is thread-safe
 * - Uses coroutine scope for async operations
 */
class ConnectionManager(
    private val unifiedWebSocketClient: UnifiedWebSocketClient,
    private val conversationApi: ConversationApi,
    private val projectApi: ProjectApi,
    private val sessionStateManager: SessionStateManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConnectionManager"

        /** Timeout for WebSocket connection establishment in milliseconds. */
        const val CONNECTION_TIMEOUT_MS = 5000L
    }

    // ============================================================================
    // Connection State Exposure
    // ============================================================================

    /**
     * Connection state exposed from the unified WebSocket client.
     */
    val connectionState: StateFlow<ConnectionState>
        get() = unifiedWebSocketClient.connectionState

    /**
     * Checks if the WebSocket is currently connected.
     */
    fun isConnected(): Boolean = unifiedWebSocketClient.isConnected()

    // ============================================================================
    // Connection Methods
    // ============================================================================

    /**
     * Connects to the unified WebSocket and subscribes to a conversation.
     * The unified client maintains a single connection per user session.
     *
     * @param token Authentication token
     * @param conversationId The full conversation ID (used for subscription)
     * @param sessionId Optional session ID for filesystem-based sessions
     * @param encodedPath Optional encoded project path
     */
    suspend fun connectUnified(
        token: String,
        conversationId: String,
        sessionId: String?,
        encodedPath: String?
    ) {
        DebugLogger.d(TAG, "connectUnified: connected=${unifiedWebSocketClient.isConnected()}, convId=${conversationId.take(30)}")

        // Connect if not already connected
        if (!unifiedWebSocketClient.isConnected()) {
            DebugLogger.d(TAG, "connectUnified: Establishing connection...")
            unifiedWebSocketClient.connect(token)

            // Wait for connection using StateFlow - more idiomatic coroutine approach
            try {
                withTimeout(CONNECTION_TIMEOUT_MS) {
                    unifiedWebSocketClient.connectionState.first { it == ConnectionState.Connected }
                }
                DebugLogger.d(TAG, "connectUnified: Connection established")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw IllegalStateException("Failed to establish unified WebSocket connection")
            }
        }

        // Subscribe to the conversation
        DebugLogger.d(TAG, "connectUnified: Subscribing to conversation...")
        unifiedWebSocketClient.subscribe(conversationId, sessionId, encodedPath)
        DebugLogger.d(TAG, "connectUnified: Subscribed successfully")
    }

    /**
     * Subscribes to history watch for real-time file change notifications via unified WebSocket.
     * Uses the conversationApi and projectApi to look up the session and project info.
     *
     * @param conversationId The conversation ID to look up
     * @param token Authentication token (unused but kept for API consistency)
     */
    suspend fun connectHistoryWatch(conversationId: String, token: String) {
        try {
            // Get conversation details to find claudeSession and project
            val conversation = conversationApi.getConversation(conversationId)
            val claudeSession = conversation.claudeSession
            if (claudeSession.isNullOrBlank()) {
                DebugLogger.d(TAG, "No claudeSession for history watch")
                return
            }

            // Get project to find the path
            val project = projectApi.getProject(conversation.projectId)
            val encodedPath = encodeProjectPath(project.path)

            // Store for later use
            sessionStateManager.updateSessionInfo(encodedPath, claudeSession)

            DebugLogger.d(TAG, "Subscribing history watch for $encodedPath / $claudeSession")
            unifiedWebSocketClient.historySubscribe(encodedPath, claudeSession)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.d(TAG, "Failed to subscribe history watch: ${e.message}")
            // Don't fail the main connection if history watch fails
        }
    }

    /**
     * Subscribes to history watch with known session info (no API lookups needed).
     *
     * @param encodedPath Encoded project path
     * @param sessionId Claude session ID
     */
    fun subscribeHistoryWatch(encodedPath: String, sessionId: String) {
        scope.launch {
            try {
                unifiedWebSocketClient.historySubscribe(encodedPath, sessionId)
            } catch (e: Exception) {
                DebugLogger.d(TAG, "Error subscribing to history watch: ${e.message}")
            }
        }
    }

    /**
     * Unsubscribes from the current conversation and history watch.
     */
    fun unsubscribe() {
        scope.launch {
            try {
                unifiedWebSocketClient.unsubscribe()
                unifiedWebSocketClient.historyUnsubscribe()
            } catch (e: Exception) {
                DebugLogger.d(TAG, "Error during unsubscribe: ${e.message}")
            }
        }
    }

    /**
     * Disconnects from the current WebSocket connection.
     * This is a manual disconnect - auto-reconnection will NOT occur.
     */
    fun disconnect() {
        scope.launch {
            try {
                // Unsubscribe from current conversation and history watch (via unified WebSocket)
                unifiedWebSocketClient.unsubscribe()
                unifiedWebSocketClient.historyUnsubscribe()

                // Disconnect the unified WebSocket connection
                unifiedWebSocketClient.disconnect()

                DebugLogger.d(TAG, "Disconnected")
            } catch (e: Exception) {
                // Ignore disconnect errors
                DebugLogger.d(TAG, "Error during disconnect: ${e.message}")
            }
        }
    }

    /**
     * Retries the connection after reconnection attempts have been exhausted.
     * Resets the reconnection state and attempts to connect again.
     */
    fun retryConnection() {
        scope.launch {
            try {
                unifiedWebSocketClient.resetAndReconnect()
            } catch (e: Exception) {
                DebugLogger.d(TAG, "Error during retry: ${e.message}")
            }
        }
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    /**
     * Encodes a project path for Claude history API.
     * Delegates to SessionStateManager.encodeProjectPath.
     */
    private fun encodeProjectPath(path: String): String {
        return SessionStateManager.encodeProjectPath(path)
    }
}
