package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.data.websocket.ConnectionState
import com.claudecode.native.data.websocket.IncomingMessage
import com.claudecode.native.data.websocket.MessageType
import com.claudecode.native.data.websocket.WebSocketClient
import com.claudecode.native.util.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chat message displayed in the UI.
 *
 * @param id Unique identifier for the message
 * @param role Who sent the message (user or assistant)
 * @param content The message text content
 * @param isStreaming True if this message is currently being streamed
 */
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val isStreaming: Boolean = false
)

/**
 * ViewModel for the chat screen, managing real-time messaging via WebSocket.
 *
 * Handles:
 * - WebSocket connection lifecycle
 * - Message sending and receiving
 * - Streaming response accumulation
 * - Connection state exposure for UI updates
 *
 * @param webSocketClient Client for WebSocket communication
 * @param apiClient API client for auth token retrieval
 * @param scope Injected coroutine scope for lifecycle management
 */
class ChatViewModel(
    private val webSocketClient: WebSocketClient,
    private val apiClient: ApiClient,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** Flow of chat messages in the conversation. */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    /** True when the assistant is actively streaming a response. */
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    /** Current content being streamed, updated incrementally. */
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Current error message, if any. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Connection state exposed from the WebSocket client. */
    val connectionState: StateFlow<ConnectionState> = webSocketClient.connectionState

    private var currentConversationId: String? = null
    private var streamingMessageId: String? = null

    init {
        // Collect incoming WebSocket messages
        scope.launch {
            webSocketClient.messages.collect { message ->
                handleIncomingMessage(message)
            }
        }
    }

    /**
     * Connects to the WebSocket for the given conversation.
     * Safe to call multiple times - will disconnect first if already connected.
     *
     * @param conversationId The conversation to connect to
     */
    fun connect(conversationId: String) {
        scope.launch {
            try {
                // Disconnect from previous conversation if any
                if (currentConversationId != null && currentConversationId != conversationId) {
                    webSocketClient.disconnect()
                }

                currentConversationId = conversationId
                val token = apiClient.getAuthToken() ?: run {
                    _error.value = "Not authenticated"
                    return@launch
                }

                webSocketClient.connect(conversationId, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUserMessage()
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
                webSocketClient.disconnect()
                currentConversationId = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ignore disconnect errors
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
                webSocketClient.resetAndReconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Retry failed"
            }
        }
    }

    /**
     * Sends a chat message to the server.
     * Creates a user message locally and sends it via WebSocket.
     *
     * @param content The message text to send
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return
        if (connectionState.value != ConnectionState.Connected) {
            _error.value = "Not connected"
            return
        }

        scope.launch {
            try {
                // Add user message to the list
                val userMessage = ChatMessage(
                    id = generateMessageId(),
                    role = MessageRole.USER,
                    content = content,
                    isStreaming = false
                )

                mutex.withLock {
                    _messages.value = _messages.value + userMessage
                }

                // Send via WebSocket
                webSocketClient.sendChat(content)

                // Prepare for streaming response
                _isStreaming.value = true
                _streamingContent.value = ""
                streamingMessageId = generateMessageId()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to send message"
                _isStreaming.value = false
            }
        }
    }

    /**
     * Stops the current generation/streaming response.
     */
    fun stopGeneration() {
        scope.launch {
            try {
                webSocketClient.sendStop()
                finalizeStreamingMessage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to stop generation"
            }
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Clears all messages from the chat.
     */
    fun clearMessages() {
        scope.launch {
            mutex.withLock {
                _messages.value = emptyList()
            }
        }
    }

    private suspend fun handleIncomingMessage(message: IncomingMessage) {
        when (message.type) {
            MessageType.STREAM -> {
                // Append streaming content atomically to avoid race conditions
                message.content?.let { chunk ->
                    _streamingContent.update { current -> current + chunk }
                }
            }

            MessageType.COMPLETE -> {
                // Finalize the streaming message
                finalizeStreamingMessage()
            }

            MessageType.ERROR -> {
                // Handle error from server
                _error.value = message.error ?: "Unknown error"
                _isStreaming.value = false
                _streamingContent.value = ""
            }

            MessageType.STATUS -> {
                // Status updates can be logged or used for UI indicators
                // Currently just acknowledging them
            }

            MessageType.PONG -> {
                // Pong response to keep-alive ping
            }
        }
    }

    private suspend fun finalizeStreamingMessage() {
        val content = _streamingContent.value
        val messageId = streamingMessageId

        if (content.isNotEmpty() && messageId != null) {
            val assistantMessage = ChatMessage(
                id = messageId,
                role = MessageRole.ASSISTANT,
                content = content,
                isStreaming = false
            )

            mutex.withLock {
                _messages.value = _messages.value + assistantMessage
            }
        }

        _isStreaming.value = false
        _streamingContent.value = ""
        streamingMessageId = null
    }

    private fun generateMessageId(): String {
        return "msg_${Clock.System.now().toEpochMilliseconds()}_${(0..9999).random()}"
    }
}
