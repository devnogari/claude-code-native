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

    private val baseUrl: String
        get() = "ws://${TokenStorage.getServerHost() ?: DEFAULT_HOST}$API_PATH"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var session: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private var currentToken: String? = null

    private var currentSessionId: String? = null
    private var currentEncodedPath: String? = null

    private val _events = MutableSharedFlow<HistoryWatchEvent>(replay = 0)
    val events: SharedFlow<HistoryWatchEvent> = _events.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private suspend fun ensureConnected(token: String) {
        if (_isConnected.value && session != null && currentToken == token) {
            return
        }

        if (currentToken != null && currentToken != token) {
            disconnectInternal()
        }

        if (connectionJob != null) {
            return
        }

        currentToken = token

        connectionJob = scope.launch {
            try {
                val wsUrl = "$baseUrl/claude-history/ws/user"

                httpClient.webSocket(urlString = wsUrl, request = {
                    headers.append("Authorization", "Bearer $token")
                }) {
                    session = this
                    _isConnected.value = true

                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> handleMessage(frame.readText())
                            is Frame.Close -> {
                                _events.emit(HistoryWatchEvent.Disconnected)
                                break
                            }
                            else -> {}
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                _events.emit(HistoryWatchEvent.Error(e.message ?: "Connection failed"))
            } finally {
                _isConnected.value = false
                session = null
                connectionJob = null
                currentToken = null
            }
        }

        // Wait for connection
        var attempts = 0
        while (!_isConnected.value && attempts < 50) {
            delay(100)
            attempts++
        }
    }

    /**
     * Subscribe to watch a specific session for file changes.
     */
    suspend fun subscribe(encodedPath: String, sessionId: String, token: String) {
        if (currentSessionId == sessionId && currentEncodedPath == encodedPath && _isConnected.value) {
            return
        }

        ensureConnected(token)

        if (!_isConnected.value) {
            _events.emit(HistoryWatchEvent.Error("Failed to establish connection"))
            return
        }

        currentSessionId = sessionId
        currentEncodedPath = encodedPath

        val subscribeMessage = HistoryWatchSubscribeMessage(
            encodedPath = encodedPath,
            sessionId = sessionId
        )
        session?.send(json.encodeToString(subscribeMessage))
    }

    /**
     * Unsubscribe from the current session without closing the connection.
     */
    suspend fun unsubscribe() {
        if (currentSessionId == null && currentEncodedPath == null) {
            return
        }

        currentSessionId = null
        currentEncodedPath = null

        if (_isConnected.value && session != null) {
            session?.send(json.encodeToString(HistoryWatchUnsubscribeMessage()))
        }
    }

    /** Legacy connect method - calls subscribe internally */
    suspend fun connect(encodedPath: String, sessionId: String, token: String) {
        subscribe(encodedPath, sessionId, token)
    }

    /** Legacy disconnect method - calls unsubscribe internally */
    suspend fun disconnect() {
        unsubscribe()
    }

    /** Close the WebSocket connection (for app shutdown or logout) */
    suspend fun close() {
        disconnectInternal()
    }

    private suspend fun disconnectInternal() {
        val job = connectionJob
        connectionJob = null
        job?.cancelAndJoin()

        try {
            session?.close()
        } catch (_: Exception) {}

        session = null
        _isConnected.value = false
        currentSessionId = null
        currentEncodedPath = null
        currentToken = null
    }

    suspend fun sendPing() {
        session?.send("""{"type":"ping"}""")
    }

    private suspend fun handleMessage(text: String) {
        try {
            val message = json.decodeFromString<HistoryWatchMessage>(text)

            when (message.type) {
                HistoryWatchMessageType.SUBSCRIBED -> {
                    _events.emit(
                        HistoryWatchEvent.Connected(
                            sessionId = message.sessionId ?: currentSessionId ?: "",
                            encodedPath = message.encodedPath ?: currentEncodedPath ?: ""
                        )
                    )
                }
                HistoryWatchMessageType.UNSUBSCRIBED -> {
                    _events.emit(HistoryWatchEvent.Unsubscribed)
                }
                HistoryWatchMessageType.NEW_MESSAGES -> {
                    val sessionId = currentSessionId
                    val encodedPath = currentEncodedPath
                    if (sessionId != null && encodedPath != null) {
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
                    _events.emit(HistoryWatchEvent.Error(message.error ?: "Unknown error"))
                }
                HistoryWatchMessageType.PONG -> {}
            }
        } catch (e: Exception) {
            _events.emit(HistoryWatchEvent.Error("Failed to parse message: ${e.message}"))
        }
    }
}
