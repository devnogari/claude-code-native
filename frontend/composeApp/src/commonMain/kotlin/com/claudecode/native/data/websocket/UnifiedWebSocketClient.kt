package com.claudecode.native.data.websocket

import com.claudecode.native.data.storage.TokenStorage
import com.claudecode.native.util.DebugLogger
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Configuration for unified WebSocket client.
 *
 * @param maxReconnectAttempts Maximum number of reconnection attempts before giving up (default: 5)
 * @param enableAutoReconnect Whether auto-reconnection is enabled (default: true)
 * @param initialDelayMs Initial delay before first reconnection attempt in milliseconds (default: 1000)
 * @param maxDelayMs Maximum delay between reconnection attempts in milliseconds (default: 30000)
 */
data class UnifiedWebSocketConfig(
    val maxReconnectAttempts: Int = 5,
    val enableAutoReconnect: Boolean = true,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 30000L
)

/**
 * Unified WebSocket client that maintains a single connection per user.
 *
 * Unlike [WebSocketClient] which creates a new connection per conversation,
 * this client connects once to `/ws/user` and uses subscribe/unsubscribe
 * messages to switch between conversations.
 *
 * Key features:
 * - Single persistent connection per user session
 * - Subscribe to conversations via messages (no reconnection needed)
 * - Session state synchronization for conversation switching
 * - Automatic reconnection with exponential backoff
 * - Concurrent message handling via Kotlin Flows
 *
 * Usage:
 * ```
 * val client = UnifiedWebSocketClient(httpClient, coroutineScope)
 * client.connect(authToken)
 * client.subscribe(conversationId)
 * client.messages.collect { message ->
 *     when (message.type) {
 *         MessageType.STREAM -> // Handle streaming content
 *         MessageType.SESSION_STATE -> // Handle state sync
 *         MessageType.COMPLETE -> // Handle completion
 *     }
 * }
 * client.sendChat("Hello, Claude!")
 * ```
 */
class UnifiedWebSocketClient(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val config: UnifiedWebSocketConfig = UnifiedWebSocketConfig()
) {
    companion object {
        private const val TAG = "UnifiedWebSocketClient"
        private const val DEFAULT_HOST = "localhost:8083"
        private const val API_PATH = "/api/v1"
        private const val SUBSCRIPTION_TIMEOUT_MS = 5000L
    }

    /** Get the current WebSocket base URL from stored server host */
    private val baseUrl: String
        get() = "ws://${TokenStorage.getServerHost() ?: DEFAULT_HOST}$API_PATH"

    private var session: WebSocketSession? = null
    private var receiveJob: Job? = null
    private var reconnectJob: Job? = null
    private val mutex = Mutex()

    // Reconnection state
    private var currentToken: String? = null
    private var reconnectAttempt = 0
    private var isManualDisconnect = false

    // Subscription state
    private val _currentSubscription = MutableStateFlow<String?>(null)
    /** Currently subscribed conversation ID */
    val currentSubscription: StateFlow<String?> = _currentSubscription.asStateFlow()

    // Cached subscription parameters for reconnection
    private var currentSessionId: String? = null
    private var currentEncodedPath: String? = null

    // Subscription confirmation - waits for server's "subscribed" message before returning
    private var pendingSubscription: CompletableDeferred<String>? = null

    private val _messages = MutableSharedFlow<IncomingMessage>()
    /** Flow of incoming messages from the server. Collectors receive all messages. */
    val messages: SharedFlow<IncomingMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    /** Current connection state. Use to update UI connection indicators. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _sessionState = MutableStateFlow<SessionStatePayload?>(null)
    /** Current session state for the subscribed conversation */
    val sessionState: StateFlow<SessionStatePayload?> = _sessionState.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Establishes a WebSocket connection to the unified user endpoint.
     *
     * @param token Authentication token for the connection
     * @throws IllegalStateException if already connected (use disconnect first)
     */
    suspend fun connect(token: String) {
        mutex.withLock {
            if (_connectionState.value == ConnectionState.Connected) {
                return
            }

            // Store connection parameters for reconnection
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
        val token = currentToken ?: return

        _connectionState.value = if (reconnectAttempt > 0) {
            ConnectionState.Reconnecting(reconnectAttempt)
        } else {
            ConnectionState.Connecting
        }

        DebugLogger.d(TAG, "Connecting to $baseUrl/ws/user (attempt: ${reconnectAttempt + 1})")

        try {
            session = httpClient.webSocketSession("$baseUrl/ws/user")

            // Send authentication token as first message
            session?.send(Frame.Text(json.encodeToString(OutgoingMessage.auth(token))))

            // Reset reconnection state on successful connection
            reconnectAttempt = 0
            _connectionState.value = ConnectionState.Connected
            DebugLogger.d(TAG, "Connected successfully to unified WebSocket")

            // Re-subscribe to current conversation if we were subscribed before
            val previousSubscription = _currentSubscription.value
            if (previousSubscription != null) {
                DebugLogger.d(TAG, "Re-subscribing to conversation: $previousSubscription")
                subscribeInternal(previousSubscription, currentSessionId, currentEncodedPath)
            }

            receiveJob = scope.launch {
                session?.let { ws ->
                    try {
                        for (frame in ws.incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    try {
                                        val message = json.decodeFromString<IncomingMessage>(text)
                                        handleMessage(message)
                                        _messages.emit(message)
                                    } catch (e: Exception) {
                                        DebugLogger.e(TAG, "Failed to parse message: ${e.message}")
                                        _messages.emit(
                                            IncomingMessage(
                                                type = MessageType.ERROR,
                                                error = "Failed to parse message: ${e.message}"
                                            )
                                        )
                                    }
                                }
                                is Frame.Close -> {
                                    DebugLogger.d(TAG, "Received Close frame")
                                    handleDisconnection()
                                }
                                else -> { /* Ignore binary and other frames */ }
                            }
                        }
                        DebugLogger.d(TAG, "Receive loop ended, connection closed")
                        handleDisconnection()
                    } catch (e: CancellationException) {
                        DebugLogger.d(TAG, "Connection cancelled")
                        throw e
                    } catch (e: Exception) {
                        DebugLogger.e(TAG, "Receive error: ${e.message}")
                        handleDisconnection(e.message ?: "Unknown error")
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Connection error: ${e.message}")
            handleConnectionError(e.message ?: "Connection failed")
        }
    }

    /**
     * Handles incoming messages that require special processing.
     */
    private fun handleMessage(message: IncomingMessage) {
        when (message.type) {
            MessageType.SUBSCRIBED -> {
                DebugLogger.d(TAG, "Subscribed to conversation: ${message.conversationId}")
                // Complete the pending subscription confirmation
                message.conversationId?.let { convId ->
                    pendingSubscription?.complete(convId)
                    pendingSubscription = null
                }
            }
            MessageType.SESSION_STATE -> {
                message.payload?.let { payload ->
                    try {
                        val state = json.decodeFromJsonElement(SessionStatePayload.serializer(), payload)
                        _sessionState.value = state
                        DebugLogger.d(TAG, "Session state updated for: ${state.conversationId}")
                    } catch (e: Exception) {
                        DebugLogger.e(TAG, "Failed to parse session state: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Handles disconnection, potentially triggering auto-reconnection.
     */
    private fun handleDisconnection(errorMessage: String? = null) {
        DebugLogger.d(TAG, "handleDisconnection called, error=$errorMessage, manual=$isManualDisconnect")
        if (isManualDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        if (errorMessage != null) {
            _connectionState.value = ConnectionState.Error(errorMessage)
        }

        scheduleReconnection()
    }

    /**
     * Handles connection errors and schedules reconnection if appropriate.
     */
    private fun handleConnectionError(errorMessage: String) {
        DebugLogger.d(TAG, "handleConnectionError: $errorMessage")
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
            DebugLogger.d(TAG, "Auto-reconnect disabled, staying disconnected")
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        if (reconnectAttempt >= config.maxReconnectAttempts) {
            DebugLogger.d(TAG, "Max reconnect attempts (${config.maxReconnectAttempts}) reached, giving up")
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

        DebugLogger.d(TAG, "Scheduling reconnection attempt $reconnectAttempt in ${delayMs}ms")

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
     * Subscribes to a conversation to receive its messages and state.
     * Waits for server confirmation before returning to prevent race conditions
     * where chat messages are sent before subscription is complete.
     *
     * @param conversationId The ID of the conversation to subscribe to
     * @param sessionId Optional session ID for resuming a specific session
     * @param encodedPath Optional encoded project path
     * @throws IllegalStateException if not connected or subscription times out
     */
    suspend fun subscribe(conversationId: String, sessionId: String? = null, encodedPath: String? = null) {
        // Create the deferred before sending to avoid race condition
        val confirmation = CompletableDeferred<String>()

        mutex.withLock {
            if (_connectionState.value != ConnectionState.Connected) {
                throw IllegalStateException("WebSocket is not connected")
            }

            // Cancel any previous pending subscription
            pendingSubscription?.cancel()
            pendingSubscription = confirmation

            // Cache subscription parameters for reconnection (before sending)
            currentSessionId = sessionId
            currentEncodedPath = encodedPath
            _sessionState.value = null // Clear previous session state

            // Send subscribe request
            val subscribeMessage = SubscribeMessage(
                conversationId = conversationId,
                sessionId = sessionId,
                encodedPath = encodedPath
            )
            session?.send(Frame.Text(json.encodeToString(SubscribeMessage.serializer(), subscribeMessage)))
            DebugLogger.d(TAG, "Sent subscribe request for conversation: $conversationId, sessionId: $sessionId")
        }

        // Wait for server confirmation outside the mutex lock
        try {
            val confirmedId = withTimeout(SUBSCRIPTION_TIMEOUT_MS) {
                confirmation.await()
            }
            _currentSubscription.value = confirmedId
            DebugLogger.d(TAG, "Subscription confirmed for conversation: $confirmedId")
        } catch (e: TimeoutCancellationException) {
            pendingSubscription = null
            DebugLogger.e(TAG, "Subscription timeout for conversation: $conversationId")
            throw IllegalStateException("Subscription timed out")
        } catch (e: CancellationException) {
            pendingSubscription = null
            throw e
        }
    }

    /**
     * Internal subscribe method for reconnection - does NOT wait for confirmation.
     * Used only during reconnection when we need to re-subscribe without blocking.
     */
    private suspend fun subscribeInternal(conversationId: String, sessionId: String? = null, encodedPath: String? = null) {
        val subscribeMessage = SubscribeMessage(
            conversationId = conversationId,
            sessionId = sessionId,
            encodedPath = encodedPath
        )
        session?.send(Frame.Text(json.encodeToString(SubscribeMessage.serializer(), subscribeMessage)))
        _currentSubscription.value = conversationId
        // Cache subscription parameters for reconnection
        currentSessionId = sessionId
        currentEncodedPath = encodedPath
        _sessionState.value = null // Clear previous session state
        DebugLogger.d(TAG, "Sent subscribe request for conversation (reconnect): $conversationId, sessionId: $sessionId")
    }

    /**
     * Unsubscribes from the current conversation.
     */
    suspend fun unsubscribe() {
        mutex.withLock {
            val currentConversationId = _currentSubscription.value ?: return@withLock

            if (_connectionState.value == ConnectionState.Connected) {
                val unsubscribeMessage = UnsubscribeMessage(conversationId = currentConversationId)
                session?.send(Frame.Text(json.encodeToString(UnsubscribeMessage.serializer(), unsubscribeMessage)))
            }

            _currentSubscription.value = null
            currentSessionId = null
            currentEncodedPath = null
            _sessionState.value = null
            DebugLogger.d(TAG, "Unsubscribed from conversation: $currentConversationId")
        }
    }

    /**
     * Sends a message to the server.
     *
     * @param message The message to send
     * @throws IllegalStateException if not connected
     */
    suspend fun send(message: OutgoingMessage) {
        DebugLogger.d(TAG, "send() called, type=${message.type}, hasContent=${message.content != null}, hasImages=${message.images?.isNotEmpty() == true}")
        mutex.withLock {
            val currentSession = session
            if (currentSession == null || _connectionState.value != ConnectionState.Connected) {
                DebugLogger.e(TAG, "ERROR - not connected! session=$currentSession, state=${_connectionState.value}")
                throw IllegalStateException("WebSocket is not connected")
            }
            val jsonMsg = json.encodeToString(message)
            DebugLogger.d(TAG, "Sending frame, length=${jsonMsg.length}, preview=${jsonMsg.take(200)}...")
            currentSession.send(Frame.Text(jsonMsg))
            DebugLogger.d(TAG, "Frame sent successfully")
        }
    }

    /**
     * Sends a chat message with the given content.
     *
     * @param content The user's message content
     */
    suspend fun sendChat(content: String) {
        DebugLogger.d(TAG, "sendChat() called, content='${content.take(50)}...'")
        send(OutgoingMessage.chat(content))
    }

    /**
     * Sends a chat message with attached images.
     *
     * @param content The user's message content
     * @param images List of base64-encoded images to attach
     */
    suspend fun sendChatWithImages(content: String, images: List<ImageContentDto>) {
        DebugLogger.d(TAG, "sendChatWithImages() called, content='${content.take(50)}...', imageCount=${images.size}")
        images.forEachIndexed { idx, img ->
            DebugLogger.d(TAG, "  image[$idx]: mediaType=${img.mediaType}, dataLength=${img.data.length}")
        }
        send(OutgoingMessage.chatWithImages(content, images))
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

            receiveJob?.cancelAndJoin()
            receiveJob = null
            try {
                session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnected"))
            } catch (e: Exception) {
                // Ignore close errors
            }
            session = null

            // Reset state
            reconnectAttempt = 0
            currentToken = null
            _currentSubscription.value = null
            currentSessionId = null
            currentEncodedPath = null
            _sessionState.value = null

            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * Resets the reconnection state and attempts to connect again.
     * Useful when the user wants to manually retry after max attempts reached.
     */
    suspend fun resetAndReconnect() {
        mutex.withLock {
            if (currentToken == null) {
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

    /**
     * Returns true if currently subscribed to a conversation.
     */
    fun isSubscribed(): Boolean = _currentSubscription.value != null
}
