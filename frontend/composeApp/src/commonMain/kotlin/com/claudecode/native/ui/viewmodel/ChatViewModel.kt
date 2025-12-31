package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.ProjectApi
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
import kotlin.time.Clock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

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
 * - Loading messages from file-based Claude history
 *
 * @param webSocketClient Client for WebSocket communication
 * @param apiClient API client for auth token retrieval
 * @param conversationApi API client for conversation operations
 * @param projectApi API client for project operations
 * @param claudeHistoryApi API client for file-based Claude history
 * @param scope Injected coroutine scope for lifecycle management
 */
class ChatViewModel(
    private val webSocketClient: WebSocketClient,
    private val apiClient: ApiClient,
    private val conversationApi: ConversationApi,
    private val projectApi: ProjectApi,
    private val claudeHistoryApi: ClaudeHistoryApi,
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
     * Also loads existing messages from the database.
     *
     * @param conversationId The conversation to connect to
     */
    fun connect(conversationId: String) {
        scope.launch {
            try {
                // Disconnect from previous conversation if any
                if (currentConversationId != null && currentConversationId != conversationId) {
                    webSocketClient.disconnect()
                    // Clear previous messages
                    mutex.withLock {
                        _messages.value = emptyList()
                    }
                }

                currentConversationId = conversationId
                val token = apiClient.getAuthToken() ?: run {
                    _error.value = "Not authenticated"
                    return@launch
                }

                // Load existing messages from file-based Claude history
                loadMessages(conversationId)

                // Connect to WebSocket for real-time updates
                webSocketClient.connect(conversationId, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUserMessage()
            }
        }
    }

    /**
     * Loads messages from file-based Claude history API.
     */
    private suspend fun loadMessages(conversationId: String) {
        try {
            // Get conversation details to find claudeSession and project
            val conversation = conversationApi.getConversation(conversationId)
            val claudeSession = conversation.claudeSession
            if (claudeSession.isNullOrBlank()) {
                // No Claude session linked - this is a new conversation
                println("ChatViewModel: No claudeSession for conversation $conversationId")
                return
            }

            // Get project to find the path
            val project = projectApi.getProject(conversation.projectId)
            val encodedPath = encodeProjectPath(project.path)
            println("ChatViewModel: Loading messages from $encodedPath / $claudeSession")

            // Load messages from file-based API (with summary=false for full content)
            val response = claudeHistoryApi.getSessionMessages(
                encodedPath = encodedPath,
                sessionId = claudeSession,
                limit = 100,
                offset = 0,
                summary = false
            )
            println("ChatViewModel: Got ${response.messages.size} messages from API")

            val chatMessages = response.messages.mapIndexedNotNull { index, msg ->
                val message = msg.message ?: return@mapIndexedNotNull null
                val role = message.role
                val content = extractTextContent(message.content)
                if (content.isBlank()) return@mapIndexedNotNull null

                ChatMessage(
                    // Use timestamp + index for unique ID (sessionId is same for all messages in session)
                    id = "msg_${msg.timestamp?.toEpochMilliseconds() ?: index}_$index",
                    role = when (role) {
                        "user" -> MessageRole.USER
                        "assistant" -> MessageRole.ASSISTANT
                        else -> return@mapIndexedNotNull null
                    },
                    content = content,
                    isStreaming = false
                )
            }
            println("ChatViewModel: Converted to ${chatMessages.size} chat messages")

            mutex.withLock {
                _messages.value = chatMessages
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 404 Not Found is expected for new conversations without history
            val isNotFound = e.message?.contains("Not found", ignoreCase = true) == true ||
                    e.message?.contains("404", ignoreCase = true) == true
            if (isNotFound) {
                println("ChatViewModel: No history found for conversation (this is normal for new chats)")
            } else {
                println("ChatViewModel: Failed to load messages: ${e.message}")
                e.printStackTrace()
                _error.value = "Failed to load messages: ${e.message}"
            }
        }
    }

    /**
     * Encodes a project path for Claude history API.
     * Claude CLI encodes /Users/name/project as -Users-name-project (keeps leading dash from root /)
     */
    private fun encodeProjectPath(path: String): String {
        // /Users/name/project -> -Users-name-project
        return path.replace("/", "-")
    }

    /**
     * Extracts text content from message content (handles string or array).
     */
    private fun extractTextContent(content: Any?): String {
        if (content == null) return ""

        return when (content) {
            is String -> cleanThinkingTags(content)
            is JsonPrimitive -> cleanThinkingTags(content.content)
            is JsonArray -> {
                content.mapNotNull { element ->
                    when (element) {
                        is JsonPrimitive -> element.content
                        is JsonObject -> {
                            val type = element["type"]?.jsonPrimitive?.content
                            when (type) {
                                "text" -> element["text"]?.jsonPrimitive?.content
                                // Filter out tool_use and tool_result - don't show them
                                "tool_use", "tool_result" -> null
                                else -> null
                            }
                        }
                        else -> null
                    }
                }.joinToString("\n").let { cleanThinkingTags(it) }
            }
            is JsonObject -> {
                val type = content["type"]?.jsonPrimitive?.content
                when (type) {
                    "text" -> cleanThinkingTags(content["text"]?.jsonPrimitive?.content ?: "")
                    // Filter out tool_use and tool_result
                    "tool_use", "tool_result" -> ""
                    else -> ""
                }
            }
            is List<*> -> {
                content.mapNotNull { item ->
                    when (item) {
                        is String -> item
                        is Map<*, *> -> {
                            val type = item["type"] as? String
                            when (type) {
                                "text" -> item["text"] as? String
                                // Filter out tool_use and tool_result
                                "tool_use", "tool_result" -> null
                                else -> null
                            }
                        }
                        else -> null
                    }
                }.joinToString("\n").let { cleanThinkingTags(it) }
            }
            else -> cleanThinkingTags(content.toString())
        }
    }

    /**
     * Removes thinking tags from content.
     */
    private fun cleanThinkingTags(text: String): String {
        return text
            .replace(Regex("<thinking>.*?</thinking>", RegexOption.DOT_MATCHES_ALL), "")
            .replace("</thinking>", "")
            .replace("<thinking>", "")
            .trim()
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
                _error.value = e.toUserMessage()
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
                _error.value = e.toUserMessage()
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
                _error.value = e.toUserMessage()
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

    /**
     * Deletes the Claude CLI session for the current conversation.
     * This removes session files from ~/.claude/ allowing a fresh start.
     *
     * @param onSuccess Callback when deletion succeeds
     * @param onError Callback when deletion fails
     */
    fun deleteSession(onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val convId = currentConversationId ?: run {
            onError("No conversation selected")
            return
        }

        scope.launch {
            try {
                conversationApi.deleteSession(convId)
                // Clear local messages after session deletion
                mutex.withLock {
                    _messages.value = emptyList()
                }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.toUserMessage())
            }
        }
    }

    private suspend fun handleIncomingMessage(message: IncomingMessage) {
        when (message.type) {
            MessageType.STREAM -> {
                // If we receive stream chunks, ensure streaming state is active
                // This handles reconnection scenarios where ViewModel was recreated
                if (!_isStreaming.value) {
                    _isStreaming.value = true
                    if (streamingMessageId == null) {
                        streamingMessageId = generateMessageId()
                    }
                }
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
