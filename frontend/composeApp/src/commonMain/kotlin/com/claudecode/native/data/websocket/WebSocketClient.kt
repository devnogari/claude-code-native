package com.claudecode.native.data.websocket

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * WebSocket client for real-time chat communication with the Claude Code backend.
 *
 * Manages WebSocket connections for streaming AI responses, handling:
 * - Connection lifecycle (connect, disconnect, reconnection)
 * - Message serialization/deserialization
 * - Connection state tracking
 * - Concurrent message handling via Kotlin Flows
 *
 * Usage:
 * ```
 * val client = WebSocketClient(httpClient)
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
    private val baseUrl: String = "ws://localhost:8080/api/v1"
) {
    private var session: WebSocketSession? = null
    private var receiveJob: Job? = null

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
        if (_connectionState.value == ConnectionState.Connected) {
            return
        }

        _connectionState.value = ConnectionState.Connecting

        try {
            session = httpClient.webSocketSession("$baseUrl/ws/$conversationId") {
                url {
                    parameters.append("token", token)
                }
            }

            _connectionState.value = ConnectionState.Connected

            receiveJob = CoroutineScope(Dispatchers.Default).launch {
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
                                    _connectionState.value = ConnectionState.Disconnected
                                }
                                else -> { /* Ignore binary and other frames */ }
                            }
                        }
                    } catch (e: CancellationException) {
                        // Normal cancellation, don't treat as error
                        throw e
                    } catch (e: Exception) {
                        _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
                    }
                }
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
        }
    }

    /**
     * Sends a message to the server.
     *
     * @param message The message to send
     * @throws IllegalStateException if not connected
     */
    suspend fun send(message: OutgoingMessage) {
        val currentSession = session
        if (currentSession == null || _connectionState.value != ConnectionState.Connected) {
            throw IllegalStateException("WebSocket is not connected")
        }
        currentSession.send(Frame.Text(json.encodeToString(message)))
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
     */
    suspend fun disconnect() {
        receiveJob?.cancel()
        receiveJob = null
        try {
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnected"))
        } catch (e: Exception) {
            // Ignore close errors
        }
        session = null
        _connectionState.value = ConnectionState.Disconnected
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
    /** Connection failed or was terminated with an error */
    data class Error(val message: String) : ConnectionState()
}
