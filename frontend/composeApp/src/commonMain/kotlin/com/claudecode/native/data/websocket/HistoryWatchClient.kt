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
}

/**
 * WebSocket client for watching Claude history session file changes in real-time
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
    private var watchJob: Job? = null

    // Track current session info for event context
    private var currentSessionId: String? = null
    private var currentEncodedPath: String? = null

    private val _events = MutableSharedFlow<HistoryWatchEvent>(replay = 0)
    val events: SharedFlow<HistoryWatchEvent> = _events.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /**
     * Connect to watch a specific session for file changes.
     * Disconnects from any existing session before connecting.
     *
     * @param encodedPath The encoded project path
     * @param sessionId The session ID to watch
     * @param token JWT token for authentication
     */
    suspend fun connect(encodedPath: String, sessionId: String, token: String) {
        disconnect()

        // Store current session info for event context
        currentSessionId = sessionId
        currentEncodedPath = encodedPath

        watchJob = scope.launch {
            try {
                val wsUrl = "$baseUrl/claude-history/ws/$encodedPath/$sessionId"
                println("HistoryWatchClient: Connecting to $wsUrl")

                httpClient.webSocket(urlString = wsUrl, request = {
                    headers.append("Authorization", "Bearer $token")
                }) {
                    session = this
                    _isConnected.value = true
                    println("HistoryWatchClient: Connected successfully to session $sessionId")

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
            } catch (e: Exception) {
                println("HistoryWatchClient: Connection error: ${e.message}")
                e.printStackTrace()
                _events.emit(HistoryWatchEvent.Error(e.message ?: "Connection failed"))
            } finally {
                println("HistoryWatchClient: Connection closed, cleaning up")
                _isConnected.value = false
                session = null
            }
        }
    }

    /**
     * Disconnect from the watch session.
     * Properly awaits job cancellation to prevent resource leaks.
     */
    suspend fun disconnect() {
        val job = watchJob
        watchJob = null

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
                    _events.emit(
                        HistoryWatchEvent.Connected(
                            sessionId = message.sessionId ?: "",
                            encodedPath = message.encodedPath ?: ""
                        )
                    )
                }
                HistoryWatchMessageType.NEW_MESSAGES -> {
                    val sessionId = currentSessionId
                    val encodedPath = currentEncodedPath
                    if (sessionId != null && encodedPath != null) {
                        message.messages?.let { messages ->
                            _events.emit(HistoryWatchEvent.NewMessages(
                                sessionId = sessionId,
                                encodedPath = encodedPath,
                                messages = messages,
                                sessionState = message.sessionState ?: SessionState.IDLE,
                                todos = message.todos ?: emptyList()
                            ))
                        }
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
