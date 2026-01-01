package com.claudecode.native.data.websocket

import com.claudecode.native.data.storage.TokenStorage
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Configuration for WebSocket reconnection behavior.
 *
 * @param maxReconnectAttempts Maximum number of reconnection attempts before giving up (default: 5)
 * @param enableAutoReconnect Whether auto-reconnection is enabled (default: true)
 * @param initialDelayMs Initial delay before first reconnection attempt in milliseconds (default: 1000)
 * @param maxDelayMs Maximum delay between reconnection attempts in milliseconds (default: 30000)
 */
data class WebSocketConfig(
    val maxReconnectAttempts: Int = 5,
    val enableAutoReconnect: Boolean = true,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 30000L
)

/**
 * WebSocket client for real-time chat communication with the Claude Code backend.
 *
 * Manages WebSocket connections for streaming AI responses, handling:
 * - Connection lifecycle (connect, disconnect, reconnection)
 * - Message serialization/deserialization
 * - Connection state tracking
 * - Concurrent message handling via Kotlin Flows
 * - Automatic reconnection with exponential backoff
 *
 * Usage:
 * ```
 * val client = WebSocketClient(httpClient, coroutineScope)
 * client.connect(conversationId, authToken)
 * client.messages.collect { message ->
 *     when (message.type) {
 *         MessageType.STREAM -> // Handle streaming content
 *         MessageType.COMPLETE -> // Handle completion
 *     }
 * }
 * client.sendChat("Hello, Claude!")
 * ```
 */
class WebSocketClient(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val config: WebSocketConfig = WebSocketConfig()
) {
    companion object {
        private const val DEFAULT_HOST = "localhost:8083"
        private const val API_PATH = "/api/v1"
    }

    /** Get the current WebSocket base URL from stored server host */
    private val baseUrl: String
        get() = "ws://${TokenStorage.getServerHost() ?: DEFAULT_HOST}$API_PATH"
    private var session: WebSocketSession? = null
    private var receiveJob: Job? = null
    private var reconnectJob: Job? = null
    private val mutex = Mutex()

    // Reconnection state
    private var currentConversationId: String? = null
    private var currentToken: String? = null
    private var reconnectAttempt = 0
    private var isManualDisconnect = false

    private val _messages = MutableSharedFlow<IncomingMessage>()
    /** Flow of incoming messages from the server. Collectors receive all messages. */
    val messages: SharedFlow<IncomingMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    /** Current connection state. Use to update UI connection indicators. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Establishes a WebSocket connection for the given conversation.
     *
     * @param conversationId The ID of the conversation to connect to
     * @param token Authentication token for the connection
     * @throws IllegalStateException if already connected (use disconnect first)
     */
    suspend fun connect(conversationId: String, token: String) {
        mutex.withLock {
            if (_connectionState.value == ConnectionState.Connected) {
                return
            }

            // Store connection parameters for reconnection
            currentConversationId = conversationId
            currentToken = token
            isManualDisconnect = false

            // Cancel any pending reconnection
            reconnectJob?.cancel()
            reconnectJob = null

            connectInternal()
        }
    }

    /**
     * Internal connection logic, called both for initial connection and reconnection.
     * Must be called within mutex lock.
     */
    private suspend fun connectInternal() {
        val conversationId = currentConversationId ?: return
        val token = currentToken ?: return

        _connectionState.value = if (reconnectAttempt > 0) {
            ConnectionState.Reconnecting(reconnectAttempt)
        } else {
            ConnectionState.Connecting
        }

        try {
            session = httpClient.webSocketSession("$baseUrl/ws/$conversationId") {
                url {
                    parameters.append("token", token)
                }
            }

            // Reset reconnection state on successful connection
            reconnectAttempt = 0
            _connectionState.value = ConnectionState.Connected

            receiveJob = scope.launch {
                session?.let { ws ->
                    try {
                        for (frame in ws.incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    try {
                                        val message = json.decodeFromString<IncomingMessage>(text)
                                        _messages.emit(message)
                                    } catch (e: Exception) {
                                        // Log parsing error but don't crash
                                        _messages.emit(
                                            IncomingMessage(
                                                type = MessageType.ERROR,
                                                error = "Failed to parse message: ${e.message}"
                                            )
                                        )
                                    }
                                }
                                is Frame.Close -> {
                                    handleDisconnection()
                                }
                                else -> { /* Ignore binary and other frames */ }
                            }
                        }
                        // Receive loop ended - connection closed
                        handleDisconnection()
                    } catch (e: CancellationException) {
                        // Normal cancellation, don't treat as error
                        throw e
                    } catch (e: Exception) {
                        handleDisconnection(e.message ?: "Unknown error")
                    }
                }
            }
        } catch (e: Exception) {
            handleConnectionError(e.message ?: "Connection failed")
        }
    }

    /**
     * Handles disconnection, potentially triggering auto-reconnection.
     */
    private fun handleDisconnection(errorMessage: String? = null) {
        if (isManualDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        if (errorMessage != null) {
            // Temporary error state before reconnection
            _connectionState.value = ConnectionState.Error(errorMessage)
        }

        scheduleReconnection()
    }

    /**
     * Handles connection errors and schedules reconnection if appropriate.
     */
    private fun handleConnectionError(errorMessage: String) {
        if (isManualDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        _connectionState.value = ConnectionState.Error(errorMessage)
        scheduleReconnection()
    }

    /**
     * Schedules a reconnection attempt with exponential backoff.
     */
    private fun scheduleReconnection() {
        if (!config.enableAutoReconnect) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        if (reconnectAttempt >= config.maxReconnectAttempts) {
            _connectionState.value = ConnectionState.Error(
                "Failed to reconnect after ${config.maxReconnectAttempts} attempts"
            )
            return
        }

        reconnectAttempt++

        // Calculate exponential backoff delay: 1s, 2s, 4s, 8s, ... up to maxDelayMs
        val delayMs = minOf(
            config.initialDelayMs * (1L shl (reconnectAttempt - 1)),
            config.maxDelayMs
        )

        reconnectJob = scope.launch {
            _connectionState.value = ConnectionState.Reconnecting(reconnectAttempt)
            delay(delayMs)

            mutex.withLock {
                if (!isManualDisconnect && _connectionState.value is ConnectionState.Reconnecting) {
                    connectInternal()
                }
            }
        }
    }

    /**
     * Sends a message to the server.
     *
     * @param message The message to send
     * @throws IllegalStateException if not connected
     */
    suspend fun send(message: OutgoingMessage) {
        mutex.withLock {
            val currentSession = session
            if (currentSession == null || _connectionState.value != ConnectionState.Connected) {
                throw IllegalStateException("WebSocket is not connected")
            }
            currentSession.send(Frame.Text(json.encodeToString(message)))
        }
    }

    /**
     * Sends a chat message with the given content.
     *
     * @param content The user's message content
     */
    suspend fun sendChat(content: String) {
        send(OutgoingMessage.chat(content))
    }

    /**
     * Sends a stop command to interrupt the current streaming response.
     */
    suspend fun sendStop() {
        send(OutgoingMessage.stop())
    }

    /**
     * Sends a ping to keep the connection alive.
     */
    suspend fun sendPing() {
        send(OutgoingMessage.ping())
    }

    /**
     * Closes the WebSocket connection and cleans up resources.
     * Safe to call even if not connected.
     * This is a manual disconnect - auto-reconnection will NOT occur.
     */
    suspend fun disconnect() {
        mutex.withLock {
            isManualDisconnect = true

            // Cancel any pending reconnection
            reconnectJob?.cancel()
            reconnectJob = null

            receiveJob?.cancelAndJoin()  // Wait for cancellation to complete
            receiveJob = null
            try {
                session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnected"))
            } catch (e: Exception) {
                // Ignore close errors
            }
            session = null

            // Reset reconnection state
            reconnectAttempt = 0
            currentConversationId = null
            currentToken = null

            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * Resets the reconnection state and attempts to connect again.
     * Useful when the user wants to manually retry after max attempts reached.
     */
    suspend fun resetAndReconnect() {
        mutex.withLock {
            if (currentConversationId == null || currentToken == null) {
                return
            }

            isManualDisconnect = false
            reconnectAttempt = 0

            // Cancel any pending reconnection
            reconnectJob?.cancel()
            reconnectJob = null

            connectInternal()
        }
    }

    /**
     * Returns true if currently connected.
     */
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.Connected
}

/**
 * Represents the current state of the WebSocket connection.
 */
sealed class ConnectionState {
    /** Not connected to the server */
    data object Disconnected : ConnectionState()
    /** Connection in progress */
    data object Connecting : ConnectionState()
    /** Successfully connected and ready to send/receive messages */
    data object Connected : ConnectionState()
    /** Attempting to reconnect after a disconnect */
    data class Reconnecting(val attempt: Int) : ConnectionState()
    /** Connection failed or was terminated with an error */
    data class Error(val message: String) : ConnectionState()
}
