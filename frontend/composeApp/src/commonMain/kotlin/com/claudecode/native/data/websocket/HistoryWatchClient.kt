package com.claudecode.native.data.websocket

import com.claudecode.native.data.model.ClaudeMessage
import com.claudecode.native.data.model.SessionState
import com.claudecode.native.data.model.TodoItem
import com.claudecode.native.data.storage.TokenStorage
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * WebSocket message types for history watching
 */
object HistoryWatchMessageType {
    const val NEW_MESSAGES = "new_messages"
    const val ERROR = "error"
    const val PING = "ping"
    const val PONG = "pong"
    const val SUBSCRIBED = "subscribed"
    const val SUBSCRIBE = "subscribe"
    const val UNSUBSCRIBE = "unsubscribe"
    const val UNSUBSCRIBED = "unsubscribed"
}

/**
 * Incoming message from history watch WebSocket
 */
@Serializable
data class HistoryWatchMessage(
    val type: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("encoded_path") val encodedPath: String? = null,
    val messages: List<ClaudeMessage>? = null,
    @SerialName("session_state") val sessionState: SessionState? = null,
    val todos: List<TodoItem>? = null,
    val error: String? = null
)

/**
 * Outgoing subscribe message
 */
@Serializable
data class HistoryWatchSubscribeMessage(
    val type: String = HistoryWatchMessageType.SUBSCRIBE,
    @SerialName("encoded_path") val encodedPath: String,
    @SerialName("session_id") val sessionId: String
)

/**
 * Outgoing unsubscribe message
 */
@Serializable
data class HistoryWatchUnsubscribeMessage(
    val type: String = HistoryWatchMessageType.UNSUBSCRIBE
)

/**
 * Sealed class representing history watch events
 */
sealed class HistoryWatchEvent {
    data class Connected(val sessionId: String, val encodedPath: String) : HistoryWatchEvent()
    data class NewMessages(
        val sessionId: String,
        val encodedPath: String,
        val messages: List<ClaudeMessage>,
        val sessionState: SessionState = SessionState.IDLE,
        val todos: List<TodoItem> = emptyList()
    ) : HistoryWatchEvent()
    data class Error(val message: String) : HistoryWatchEvent()
    data object Disconnected : HistoryWatchEvent()
    data object Unsubscribed : HistoryWatchEvent()
}

/**
 * WebSocket client for watching Claude history session file changes in real-time.
 * Uses a unified connection with subscribe/unsubscribe pattern for efficiency.
 */
class HistoryWatchClient(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope
) {
    companion object {
        private const val DEFAULT_HOST = "localhost:8083"
        private const val API_PATH = "/api/v1"
    }

    /** Get the current WebSocket base URL from stored server host */
    private val baseUrl: String
        get() = "ws://${TokenStorage.getServerHost() ?: DEFAULT_HOST}$API_PATH"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var session: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private var currentToken: String? = null

    // Track current subscription
    private var currentSessionId: String? = null
    private var currentEncodedPath: String? = null

    private val _events = MutableSharedFlow<HistoryWatchEvent>(replay = 0)
    val events: SharedFlow<HistoryWatchEvent> = _events.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /**
     * Ensure connection to the unified WebSocket endpoint.
     * If already connected, just sends a subscribe message.
     */
    private suspend fun ensureConnected(token: String) {
        if (_isConnected.value && session != null && currentToken == token) {
            return // Already connected
        }

        // Disconnect if token changed
        if (currentToken != null && currentToken != token) {
            disconnectInternal()
        }

        if (connectionJob != null) {
            return // Connection in progress
        }

        currentToken = token

        connectionJob = scope.launch {
            try {
                val wsUrl = "$baseUrl/claude-history/ws/user"
                println("HistoryWatchClient: Connecting to unified endpoint $wsUrl")

                httpClient.webSocket(urlString = wsUrl, request = {
                    headers.append("Authorization", "Bearer $token")
                }) {
                    session = this
                    _isConnected.value = true
                    println("HistoryWatchClient: Connected to unified endpoint")

                    // Listen for messages
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                handleMessage(text)
                            }
                            is Frame.Close -> {
                                println("HistoryWatchClient: Received Close frame")
                                _events.emit(HistoryWatchEvent.Disconnected)
                                break
                            }
                            else -> {}
                        }
                    }
                    println("HistoryWatchClient: Receive loop ended")
                }
            } catch (e: CancellationException) {
                // Normal cancellation - don't log as error
                println("HistoryWatchClient: Connection cancelled")
            } catch (e: Exception) {
                println("HistoryWatchClient: Connection error: ${e.message}")
                _events.emit(HistoryWatchEvent.Error(e.message ?: "Connection failed"))
            } finally {
                println("HistoryWatchClient: Connection closed, cleaning up")
                _isConnected.value = false
                session = null
                connectionJob = null
                currentToken = null
            }
        }

        // Wait for connection to be established
        var attempts = 0
        while (!_isConnected.value && attempts < 50) { // 5 seconds max
            delay(100)
            attempts++
        }
    }

    /**
     * Subscribe to watch a specific session for file changes.
     * Uses the unified connection - no need to reconnect.
     *
     * @param encodedPath The encoded project path
     * @param sessionId The session ID to watch
     * @param token JWT token for authentication
     */
    suspend fun subscribe(encodedPath: String, sessionId: String, token: String) {
        // If already subscribed to the same session, skip
        if (currentSessionId == sessionId && currentEncodedPath == encodedPath && _isConnected.value) {
            println("HistoryWatchClient: Already subscribed to $sessionId, skipping")
            return
        }

        // Ensure we have a connection
        ensureConnected(token)

        if (!_isConnected.value) {
            println("HistoryWatchClient: Failed to establish connection")
            _events.emit(HistoryWatchEvent.Error("Failed to establish connection"))
            return
        }

        // Update tracking
        currentSessionId = sessionId
        currentEncodedPath = encodedPath

        // Send subscribe message
        val subscribeMessage = HistoryWatchSubscribeMessage(
            encodedPath = encodedPath,
            sessionId = sessionId
        )
        val messageJson = json.encodeToString(subscribeMessage)
        println("HistoryWatchClient: Sending subscribe for $encodedPath/$sessionId")
        session?.send(messageJson)
    }

    /**
     * Unsubscribe from the current session without closing the connection.
     */
    suspend fun unsubscribe() {
        if (currentSessionId == null && currentEncodedPath == null) {
            return // Not subscribed
        }

        currentSessionId = null
        currentEncodedPath = null

        if (_isConnected.value && session != null) {
            val unsubscribeMessage = HistoryWatchUnsubscribeMessage()
            val messageJson = json.encodeToString(unsubscribeMessage)
            println("HistoryWatchClient: Sending unsubscribe")
            session?.send(messageJson)
        }
    }

    /**
     * Legacy connect method for backward compatibility.
     * Internally calls subscribe.
     */
    suspend fun connect(encodedPath: String, sessionId: String, token: String) {
        subscribe(encodedPath, sessionId, token)
    }

    /**
     * Legacy disconnect method for backward compatibility.
     * Internally calls unsubscribe (doesn't close the connection).
     */
    suspend fun disconnect() {
        unsubscribe()
    }

    /**
     * Actually close the WebSocket connection.
     * Only call this when the app is closing or user logs out.
     */
    suspend fun close() {
        disconnectInternal()
    }

    private suspend fun disconnectInternal() {
        val job = connectionJob
        connectionJob = null

        // Cancel and wait for the job to complete
        job?.cancelAndJoin()

        // Close the session after job is cancelled
        try {
            session?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
        session = null
        _isConnected.value = false
        currentSessionId = null
        currentEncodedPath = null
        currentToken = null
    }

    /**
     * Send a ping message
     */
    suspend fun sendPing() {
        session?.send("""{"type":"ping"}""")
    }

    private suspend fun handleMessage(text: String) {
        try {
            val message = json.decodeFromString<HistoryWatchMessage>(text)

            when (message.type) {
                HistoryWatchMessageType.SUBSCRIBED -> {
                    println("HistoryWatchClient: Subscribed to ${message.sessionId}")
                    _events.emit(
                        HistoryWatchEvent.Connected(
                            sessionId = message.sessionId ?: currentSessionId ?: "",
                            encodedPath = message.encodedPath ?: currentEncodedPath ?: ""
                        )
                    )
                }
                HistoryWatchMessageType.UNSUBSCRIBED -> {
                    println("HistoryWatchClient: Unsubscribed")
                    _events.emit(HistoryWatchEvent.Unsubscribed)
                }
                HistoryWatchMessageType.NEW_MESSAGES -> {
                    val sessionId = currentSessionId
                    val encodedPath = currentEncodedPath
                    if (sessionId != null && encodedPath != null) {
                        // Always emit event when sessionState is present, even if messages is null/empty.
                        // Backend uses `omitempty` which omits empty arrays, resulting in null here.
                        // This is critical for detecting STREAMING -> IDLE state transitions.
                        _events.emit(HistoryWatchEvent.NewMessages(
                            sessionId = sessionId,
                            encodedPath = encodedPath,
                            messages = message.messages ?: emptyList(),
                            sessionState = message.sessionState ?: SessionState.IDLE,
                            todos = message.todos ?: emptyList()
                        ))
                    }
                }
                HistoryWatchMessageType.ERROR -> {
                    println("HistoryWatchClient: Server error: ${message.error}")
                    _events.emit(HistoryWatchEvent.Error(message.error ?: "Unknown error"))
                }
                HistoryWatchMessageType.PONG -> {
                    // Pong received, connection is alive
                }
            }
        } catch (e: Exception) {
            println("HistoryWatchClient: Failed to parse message: ${e.message}")
            e.printStackTrace()
            _events.emit(HistoryWatchEvent.Error("Failed to parse message: ${e.message}"))
        }
    }
}
