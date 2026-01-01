package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.api.CommandApi
import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.model.Command
import com.claudecode.native.data.model.ExecuteCommandResponse
import com.claudecode.native.data.model.ExecuteContext
import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.data.websocket.ConnectionState
import com.claudecode.native.data.websocket.HistoryWatchClient
import com.claudecode.native.data.websocket.HistoryWatchEvent
import com.claudecode.native.data.websocket.IncomingMessage
import com.claudecode.native.data.websocket.MessageType
import com.claudecode.native.data.websocket.WebSocketClient
import com.claudecode.native.util.toUserMessage
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * Tool usage information for display in the UI.
 *
 * @param id Unique identifier for the tool use
 * @param name Tool name (Read, Edit, Bash, etc.)
 * @param summary Brief summary of the tool input
 * @param result Full result content (shown when expanded)
 * @param isError Whether the result is an error
 */
data class ToolUseInfo(
    val id: String,
    val name: String,
    val summary: String,
    val result: String? = null,
    val isError: Boolean = false
)

/**
 * Content block in a chat message, preserving the order of text and tool usages.
 * Claude responses can interleave text and tool_use blocks, and this sealed class
 * preserves that ordering for accurate display.
 */
sealed class ContentBlock {
    /** Text content block */
    data class Text(val content: String) : ContentBlock()
    /** Tool usage block */
    data class Tool(val info: ToolUseInfo) : ContentBlock()
}

/**
 * Chat message displayed in the UI.
 *
 * @param id Unique identifier for the message
 * @param role Who sent the message (user or assistant)
 * @param blocks Ordered list of content blocks (text and tools interleaved)
 * @param isStreaming True if this message is currently being streamed
 * @param isPending True if this message is pending confirmation from server (user messages only)
 */
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val blocks: List<ContentBlock>,
    val isStreaming: Boolean = false,
    val isPending: Boolean = false
) {
    /** Convenience property: concatenated text content for searching/matching */
    val content: String
        get() = blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n\n") { it.content }

    /** Convenience property: list of tools for backwards compatibility */
    val tools: List<ToolUseInfo>
        get() = blocks.filterIsInstance<ContentBlock.Tool>().map { it.info }
}

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
    private val historyWatchClient: HistoryWatchClient,
    private val commandApi: CommandApi,
    private val scope: CoroutineScope
) {
    /**
     * LOCK ORDERING (always acquire in this order to prevent deadlocks):
     * 1. streamingMutex - protects streaming state (_isStreaming, _streamingContent, etc.)
     * 2. mutex - protects message list (_messages)
     * 3. mapsMutex - protects tool tracking maps (sessionToolUses, sessionToolResults)
     *
     * When multiple locks are needed, acquire in this order. Never acquire a higher-numbered
     * lock while holding a lower-numbered one.
     */
    private val mutex = Mutex()  // Lock #2: protects _messages updates

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** Flow of chat messages in the conversation. */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    /** True when the assistant is actively streaming a response. */
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    /** Current content being streamed, updated incrementally. */
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _streamingTools = MutableStateFlow<List<ToolUseInfo>>(emptyList())
    /** Current tools being used during streaming. */
    val streamingTools: StateFlow<List<ToolUseInfo>> = _streamingTools.asStateFlow()

    private val _streamingBlocks = MutableStateFlow<List<ContentBlock>>(emptyList())
    /** Ordered content blocks during streaming (text and tools interleaved). */
    val streamingBlocks: StateFlow<List<ContentBlock>> = _streamingBlocks.asStateFlow()

    private val _queuedMessages = MutableStateFlow<List<String>>(emptyList())
    /** Messages queued while streaming is in progress. */
    val queuedMessages: StateFlow<List<String>> = _queuedMessages.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Current error message, if any. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _conversationTitle = MutableStateFlow<String?>(null)
    /** Current conversation title for display in UI. */
    val conversationTitle: StateFlow<String?> = _conversationTitle.asStateFlow()

    private val _isDraftSession = MutableStateFlow(false)
    /** True when this is a draft session (not yet created on server). */
    val isDraftSession: StateFlow<Boolean> = _isDraftSession.asStateFlow()

    private val _sessionCreatedEvent = MutableStateFlow<String?>(null)
    /**
     * Emits the new session ID when a draft session is converted to a real session.
     * UI can observe this to update the sidebar/project list.
     */
    val sessionCreatedEvent: StateFlow<String?> = _sessionCreatedEvent.asStateFlow()

    /** Pending session ID to emit when first HistoryWatch message arrives (deferred until session folder exists). */
    private var pendingSessionCreatedEmit: String? = null


    private val _availableCommands = MutableStateFlow<List<Command>>(emptyList())
    /** Available slash commands (builtin + custom). */
    val availableCommands: StateFlow<List<Command>> = _availableCommands.asStateFlow()

    private val _commandsLoading = MutableStateFlow(false)
    /** True when commands are being loaded from the server. */
    val commandsLoading: StateFlow<Boolean> = _commandsLoading.asStateFlow()

    private val _scrollToBottomSignal = MutableStateFlow(0)
    /**
     * Signal for UI to scroll to bottom. Incremented when:
     * - Streaming completes (finalizeStreamingMessage)
     * - User sends a message
     * UI should observe and scroll when value changes.
     */
    val scrollToBottomSignal: StateFlow<Int> = _scrollToBottomSignal.asStateFlow()

    /** Connection state exposed from the WebSocket client. */
    val connectionState: StateFlow<ConnectionState> = webSocketClient.connectionState

    private var currentConversationId: String? = null
    private var streamingMessageId: String? = null
    private var currentEncodedPath: String? = null
    private var currentClaudeSession: String? = null
    private var currentProjectPath: String? = null  // Original project path for API calls

    // Debounce job for detecting streaming completion from HistoryWatch
    // When streaming is activated by HistoryWatch (not WebSocket), we need to detect
    // completion by observing that no new assistant messages arrive for a period
    private var historyWatchStreamingDebounceJob: Job? = null
    private var loadCommandsJob: Job? = null
    private var currentConnectJob: Job? = null  // Track current connect job to cancel on room switch
    private var isStreamingFromHistoryWatch = false
    private val historyWatchStreamingTimeout = 3000L // 3 seconds - accounts for tool execution delays

    // Session-level tool tracking for matching tool_use with tool_result across messages
    // Using mutableMapOf with Mutex for thread safety across WebSocket and HistoryWatch handlers
    // (ConcurrentHashMap is not available in Kotlin Multiplatform)
    private val sessionToolUses = mutableMapOf<String, ToolUseInfo>()
    private val sessionToolResults = mutableMapOf<String, Pair<String, Boolean>>()

    // Track pending user messages to properly handle duplicates from history watch
    // Key: content hash, Value: message ID
    private val pendingUserMessages = mutableMapOf<Int, String>()

    // Track finalized assistant messages to prevent duplicates from history watch
    // Key: content hash (first 200 chars), Value: message ID
    private val finalizedAssistantMessages = mutableMapOf<Int, String>()

    // Track processed history watch message timestamps to prevent re-processing
    // This is more reliable than content-based dedup for rapid message sequences
    // Limited to MAX_PROCESSED_TIMESTAMPS entries to prevent unbounded growth
    private val processedHistoryWatchTimestamps = mutableSetOf<Long>()
    companion object {
        /** Marker for draft sessions that haven't been created yet. */
        const val DRAFT_SESSION_MARKER = "draft"
        /** Number of attempts to wait for WebSocket connection. */
        private const val CONNECTION_TIMEOUT_ATTEMPTS = 50
        /** Interval between connection checks in milliseconds. */
        private const val CONNECTION_CHECK_INTERVAL_MS = 100L
        private const val MAX_PROCESSED_TIMESTAMPS = 500
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Normalizes content for hash comparison.
         * Trims whitespace and normalizes internal whitespace to single spaces.
         * This ensures hash comparison works even when content is modified
         * during serialization/deserialization (e.g., trailing spaces removed).
         */
        fun normalizeForComparison(content: String): String {
            return content.trim().replace(WHITESPACE_REGEX, " ")
        }
    }

    private val mapsMutex = Mutex()  // Lock #3: protects sessionToolUses, sessionToolResults

    private val streamingMutex = Mutex()  // Lock #1: protects streaming state (see lock ordering above)

    // Maximum number of queued messages to prevent memory pressure
    private val maxQueuedMessages = 10


    init {
        // Collect incoming WebSocket messages
        scope.launch {
            webSocketClient.messages.collect { message ->
                handleIncomingMessage(message)
            }
        }

        // Collect history watch events for real-time file changes
        scope.launch {
            historyWatchClient.events.collect { event ->
                handleHistoryWatchEvent(event)
            }
        }
    }

    /**
     * Connects to the WebSocket for the given conversation.
     * Safe to call multiple times - will disconnect first if already connected.
     * Also loads existing messages from the filesystem-based Claude history.
     *
     * @param conversationId The conversation identifier, either:
     *   - Draft format: "draft?project=encodedPath" (new session, not yet created)
     *   - New format: "sessionId?project=encodedPath" (from filesystem-based ProjectList)
     *   - Legacy format: UUID conversation ID (from database)
     */
    fun connect(conversationId: String) {
        // Generate unique ID for this connect call for debugging
        val connectCallId = "CONN-${(0..9999).random()}"
        val shortConvId = conversationId.take(20)

        // OPTIMIZATION: Skip if already connected to this room and not loading
        if (currentConversationId == conversationId && currentConnectJob?.isActive != true) {
            println("[$connectCallId] SKIP: Already connected to $shortConvId")
            return
        }

        println("[$connectCallId] >>> connect() CALLED with: $shortConvId")
        println("[$connectCallId] Current state: currentConversationId=${currentConversationId?.take(20)}, title=${_conversationTitle.value?.take(30)}")

        // Cancel any previous connect job to prevent race conditions when switching rooms quickly
        val prevJob = currentConnectJob
        if (prevJob != null) {
            println("[$connectCallId] Cancelling previous connect job")
            prevJob.cancel()
        }

        currentConnectJob = scope.launch {
            try {
                println("[$connectCallId] Inside coroutine, checking if need to disconnect")
                // Disconnect from previous conversation if any
                if (currentConversationId != null && currentConversationId != conversationId) {
                    webSocketClient.disconnect()
                    historyWatchClient.disconnect()

                    // Cancel pending debounce job from previous session
                    historyWatchStreamingDebounceJob?.cancel()
                    historyWatchStreamingDebounceJob = null
                    isStreamingFromHistoryWatch = false

                    // NOTE: We intentionally DO NOT clear messages/title here anymore.
                    // Old messages remain visible during loading to prevent empty screen flash.
                    // State will be replaced atomically when new data loads successfully.
                    // If guard check fails (user switched rooms), we abort without clearing.

                    // Only clear transient streaming state
                    _isStreaming.value = false
                    _streamingContent.value = ""
                    _streamingBlocks.value = emptyList()
                    _streamingTools.value = emptyList()
                    _queuedMessages.value = emptyList()
                    _error.value = null
                    _conversationTitle.value = null
                    _isDraftSession.value = false
                    _sessionCreatedEvent.value = null
                    streamingMessageId = null
                }

                // Set conversation ID immediately so subsequent connect() calls know to disconnect
                currentConversationId = conversationId
                println("[$connectCallId] Set currentConversationId = $shortConvId")

                val token = apiClient.getAuthToken() ?: run {
                    _error.value = "Not authenticated"
                    return@launch
                }

                // Parse conversationId to extract session info
                // Format: "draft?project=encodedPath", "sessionId?project=encodedPath", or legacy UUID
                val (sessionId, encodedPath) = parseConversationId(conversationId)
                println("[$connectCallId] Parsed: sessionId=${sessionId?.take(15)}, encodedPath=${encodedPath?.take(30)}")

                if (sessionId != null && encodedPath != null) {
                    // Check if this is a draft session (not yet created)
                    if (sessionId == DRAFT_SESSION_MARKER) {
                        println("ChatViewModel: Draft session mode for $encodedPath")
                        _isDraftSession.value = true
                        currentEncodedPath = encodedPath
                        currentClaudeSession = null  // No session ID yet
                        _conversationTitle.value = "New Chat"

                        // Fetch project info for project path and commands
                        try {
                            val project = claudeHistoryApi.getProject(encodedPath)
                            currentProjectPath = project.path
                            loadCommands(project.path)
                        } catch (e: Exception) {
                            println("ChatViewModel: Failed to fetch project info for draft: ${e.message}")
                        }

                        // Connect WebSocket in draft mode (will be ready when session is created)
                        // The WebSocket will be reconnected with actual session ID when first message is sent
                        println("ChatViewModel: Draft mode - WebSocket will connect on first message")
                        return@launch  // Don't connect WebSocket yet for draft sessions
                    }

                    // New filesystem-based flow (existing session)
                    currentEncodedPath = encodedPath
                    currentClaudeSession = sessionId

                    println("[$connectCallId] BEFORE loadMessagesFromFilesystem, title=${_conversationTitle.value?.take(30)}")

                    // Load messages directly from filesystem API
                    loadMessagesFromFilesystem(encodedPath, sessionId, conversationId, connectCallId)

                    println("[$connectCallId] AFTER loadMessagesFromFilesystem, title=${_conversationTitle.value?.take(30)}")
                    println("[$connectCallId] currentConversationId now = ${currentConversationId?.take(20)}")

                    // Guard: Check if we're still the active conversation after async load
                    // If user switched rooms during loading, abort this connection
                    if (currentConversationId != conversationId) {
                        println("[$connectCallId] !!! GUARD TRIGGERED: room switched during load, ABORTING")
                        println("[$connectCallId] BUT TITLE IS ALREADY SET TO: ${_conversationTitle.value?.take(50)}")
                        return@launch
                    }
                    println("[$connectCallId] Guard passed, continuing with connection")

                    // Connect to history watch for real-time file changes
                    println("ChatViewModel: Connecting history watch for $encodedPath / $sessionId")
                    historyWatchClient.connect(encodedPath, sessionId, token)

                    // Connect WebSocket with the full session identifier
                    // This allows continuing the conversation
                    println("[$connectCallId] Connecting WebSocket for filesystem session")
                    webSocketClient.connect(conversationId, token)
                    println("[$connectCallId] <<< connect() COMPLETE for: $shortConvId")
                    println("[$connectCallId] Final state: title=${_conversationTitle.value?.take(40)}, msgCount=${_messages.value.size}")
                } else {
                    // Legacy database-based flow (fallback)
                    loadMessages(conversationId)

                    // Guard: Check if we're still the active conversation after async load
                    if (currentConversationId != conversationId) {
                        println("[$connectCallId] !!! Legacy GUARD TRIGGERED: room switched during message load")
                        return@launch
                    }

                    webSocketClient.connect(conversationId, token)
                    connectHistoryWatch(conversationId, token)
                    println("[$connectCallId] <<< Legacy connect() COMPLETE")
                }
            } catch (e: CancellationException) {
                println("[$connectCallId] !!! CANCELLED - job was cancelled")
                throw e
            } catch (e: Exception) {
                println("[$connectCallId] !!! ERROR: ${e.message}")
                _error.value = e.toUserMessage()
            }
        }
    }

    /**
     * Parses the conversationId to extract session ID and encoded path.
     * @return Pair of (sessionId, encodedPath) or (null, null) for legacy format
     */
    private fun parseConversationId(conversationId: String): Pair<String?, String?> {
        if (!conversationId.contains("?project=")) {
            return Pair(null, null)
        }
        val parts = conversationId.split("?project=")
        if (parts.size != 2) {
            return Pair(null, null)
        }
        return Pair(parts[0], parts[1])
    }

    /**
     * Loads messages directly from filesystem-based Claude history API.
     * Also fetches project info to store the original project path and session title.
     */
    private suspend fun loadMessagesFromFilesystem(
        encodedPath: String,
        sessionId: String,
        expectedConversationId: String,
        callId: String = "?"
    ) {
        try {
            println("[$callId] loadMessagesFromFilesystem START - sessionId=${sessionId.take(15)}")
            println("[$callId] Before API call, currentConversationId=${currentConversationId?.take(20)}")

            // Fetch project to get the original path and session title for delete operations
            try {
                val project = claudeHistoryApi.getProject(encodedPath)

                // GUARD CHECK: Before setting ANY state, verify we're still the active conversation
                // This prevents race condition where user clicks another room during API call
                if (currentConversationId != expectedConversationId) {
                    println("[$callId] !!! GUARD: Room switched during project fetch, aborting state update")
                    println("[$callId] Expected: ${expectedConversationId.take(20)}, Current: ${currentConversationId?.take(20)}")
                    return
                }

                currentProjectPath = project.path
                println("[$callId] Got project: ${project.name}")

                // Find the session and extract the title (firstMessage)
                val session = project.sessions.find { it.id == sessionId }
                val newTitle = if (session != null && session.firstMessage.isNotBlank()) {
                    session.firstMessage
                } else {
                    project.name
                }

                // GUARD CHECK again before setting title (in case of context switch)
                if (currentConversationId != expectedConversationId) {
                    println("[$callId] !!! GUARD: Room switched before title set, aborting")
                    return
                }

                println("[$callId] About to set title to: ${newTitle.take(50)}")
                println("[$callId] currentConversationId at title set time: ${currentConversationId?.take(20)}")
                _conversationTitle.value = newTitle
                println("[$callId] Title IS NOW: ${_conversationTitle.value?.take(50)}")

                // Load commands now that we have the project path
                loadCommands(project.path)
            } catch (e: Exception) {
                println("[$callId] Failed to fetch project info: ${e.message}")
                // Continue without project path - delete will try to decode
                _conversationTitle.value = null
            }

            // GUARD CHECK before loading messages
            if (currentConversationId != expectedConversationId) {
                println("[$callId] !!! GUARD: Room switched before message load, aborting")
                return
            }

            // Load messages from file-based API (with summary=false for full content)
            try {
                val response = claudeHistoryApi.getSessionMessages(
                    encodedPath = encodedPath,
                    sessionId = sessionId,
                    limit = 100,
                    offset = 0,
                    summary = false
                )

                // GUARD CHECK after API call, before setting messages
                if (currentConversationId != expectedConversationId) {
                    println("[$callId] !!! GUARD: Room switched during message fetch, discarding ${response.messages.size} messages")
                    return
                }

                println("[$callId] Got ${response.messages.size} messages from API, processing...")

                // Clear old state atomically ONLY after guard check passes
                // This prevents empty view flash when rapidly switching rooms
                mapsMutex.withLock {
                    sessionToolUses.clear()
                    sessionToolResults.clear()
                    pendingUserMessages.clear()
                    finalizedAssistantMessages.clear()
                    processedHistoryWatchTimestamps.clear()
                }
                mutex.withLock {
                    _messages.value = emptyList()
                }

                // Process messages using existing logic
                processLoadedMessages(response.messages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 404 Not Found is expected for new sessions without history
                // This is normal - the session file doesn't exist yet
                val isNotFound = e.message?.contains("Not found", ignoreCase = true) == true ||
                        e.message?.contains("404", ignoreCase = true) == true ||
                        e.message?.contains("resource not found", ignoreCase = true) == true
                if (isNotFound) {
                    println("[$callId] No history found for session (this is normal for new chats)")

                    // GUARD CHECK: Verify we're still the active conversation before clearing state
                    if (currentConversationId != expectedConversationId) {
                        println("[$callId] !!! GUARD: Room switched during 404 handling, aborting state clear")
                        return
                    }

                    // Clear old state for new sessions - this is the FIX for stale messages
                    // Previously we only cleared on successful message load, leaving old messages
                    // visible when starting a new session
                    mapsMutex.withLock {
                        sessionToolUses.clear()
                        sessionToolResults.clear()
                        pendingUserMessages.clear()
                        finalizedAssistantMessages.clear()
                        processedHistoryWatchTimestamps.clear()
                    }
                    mutex.withLock {
                        _messages.value = emptyList()
                    }
                    println("[$callId] Cleared state for new session")
                } else {
                    println("[$callId] Failed to load messages from filesystem: ${e.message}")
                    _error.value = e.toUserMessage()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Outer catch for project fetch errors
            println("ChatViewModel: Failed during filesystem message loading: ${e.message}")
            _error.value = e.toUserMessage()
        }
    }

    /**
     * Processes loaded ClaudeMessages into ChatMessages and updates the UI.
     * Two-pass parsing to match tool_use with tool_result across messages.
     */
    private suspend fun processLoadedMessages(messages: List<com.claudecode.native.data.model.ClaudeMessage>) {
        // IMPORTANT: Do NOT sort by timestamp!
        // The backend returns messages in correct chronological order from the JSONL file.
        // Sorting by timestamp breaks the order because:
        // 1. Multiple messages can have identical timestamps
        // 2. Kotlin's sortedWith() doesn't guarantee stable ordering for equal elements
        // Trust the file order - it's the source of truth.

        // Clear and rebuild session-level tool tracking with proper synchronization
        // Copy tool uses for later use outside the lock to avoid holding lock during message processing
        val allToolUses: Map<String, ToolUseInfo>
        mapsMutex.withLock {
            sessionToolUses.clear()
            sessionToolResults.clear()

            // Pass 1: Collect all tool_uses and tool_results
            for (msg in messages) {
                val message = msg.message ?: continue
                extractToolsFromContent(message.content, sessionToolUses, sessionToolResults)
            }

            // Match tool_results to tool_uses
            for ((toolId, resultPair) in sessionToolResults) {
                val (result, isError) = resultPair
                if (sessionToolUses.containsKey(toolId)) {
                    sessionToolUses[toolId] = sessionToolUses[toolId]!!.copy(result = result, isError = isError)
                }
            }

            // Create a snapshot of tool uses for use outside the lock
            allToolUses = sessionToolUses.toMap()
        }

        // Pass 2: Create chat messages with matched tools (using original file order)
        val chatMessages = messages.mapIndexedNotNull { index, msg ->
            val message = msg.message ?: return@mapIndexedNotNull null
            val role = message.role
            val blocks = parseMessageContent(message.content)

            // Update tool blocks with results from session maps
            val blocksWithResults = blocks.map { block ->
                when (block) {
                    is ContentBlock.Tool -> {
                        val toolWithResult = allToolUses[block.info.id]
                        if (toolWithResult != null) ContentBlock.Tool(toolWithResult) else block
                    }
                    else -> block
                }
            }

            // Skip tool_result-only messages (they're matched to tool_use messages)
            if (hasOnlyToolResults(message.content)) {
                return@mapIndexedNotNull null
            }

            // Skip messages with no content blocks
            if (blocksWithResults.isEmpty()) return@mapIndexedNotNull null

            // Get text content for validation
            val textContent = blocksWithResults.filterIsInstance<ContentBlock.Text>()
                .joinToString("\n\n") { it.content }

            // Skip compaction/summary messages (system-generated, not user content)
            if (isCompactionMessage(textContent)) return@mapIndexedNotNull null

            ChatMessage(
                // Use timestamp + index for unique ID (sessionId is same for all messages in session)
                // Use "loaded_" prefix to avoid collision with generateMessageId() which uses "msg_" prefix
                id = "loaded_${msg.timestamp?.toEpochMilliseconds() ?: index}_$index",
                role = when (role) {
                    "user" -> MessageRole.USER
                    "assistant" -> MessageRole.ASSISTANT
                    else -> return@mapIndexedNotNull null
                },
                blocks = blocksWithResults,
                isStreaming = false
            )
        }
        println("ChatViewModel: Converted to ${chatMessages.size} chat messages")

        mutex.withLock {
            val currentMessages = _messages.value
            // Preserve pending messages that haven't been confirmed by history watch yet
            // These are locally added messages waiting for filesystem sync
            val pendingMessages = currentMessages.filter { it.isPending }

            // Preserve streaming message if active - this prevents losing tool messages
            // during filesystem reload while streaming is happening
            val streamingMessage: ChatMessage? = if (_isStreaming.value) {
                val blocks = _streamingBlocks.value
                val msgId = streamingMessageId
                if (blocks.isNotEmpty() && msgId != null) {
                    println("ChatViewModel: Preserving streaming message with ${blocks.size} blocks during reload")
                    ChatMessage(
                        id = msgId,
                        role = MessageRole.ASSISTANT,
                        blocks = blocks,
                        isStreaming = true
                    )
                } else null
            } else null

            val messagesToAdd = mutableListOf<ChatMessage>()

            if (pendingMessages.isNotEmpty()) {
                println("ChatViewModel: Preserving ${pendingMessages.size} pending messages during reload")
                // Merge: loaded messages + pending messages not already in loaded list
                val loadedContentHashes = chatMessages.map { it.content.hashCode() }.toSet()
                val uniquePendingMessages = pendingMessages.filter { pending ->
                    pending.content.hashCode() !in loadedContentHashes
                }
                messagesToAdd.addAll(uniquePendingMessages)
            }

            // Add streaming message if it wasn't already in loaded messages
            if (streamingMessage != null) {
                val streamingContentHash = streamingMessage.content.hashCode()
                val alreadyLoaded = chatMessages.any { it.content.hashCode() == streamingContentHash }
                if (!alreadyLoaded) {
                    messagesToAdd.add(streamingMessage)
                }
            }

            // Build a map of (normalized content + role + approximate position) -> existing ID
            // This preserves IDs across reload while handling duplicate content correctly
            // We group by content+role and track all messages with that content to match by position
            val existingByContentAndRole = currentMessages.groupBy { msg ->
                "${msg.role}_${normalizeForComparison(msg.content)}"
            }

            // Track which existing IDs have been used to avoid duplicates
            val usedIds = mutableSetOf<String>()

            // Update loaded messages to preserve existing IDs where content matches
            // For duplicate content, match by position within the duplicate group
            val contentRoleCounters = mutableMapOf<String, Int>()
            val stableMessages = chatMessages.map { msg ->
                val key = "${msg.role}_${normalizeForComparison(msg.content)}"
                val existingList = existingByContentAndRole[key]

                if (existingList != null && existingList.isNotEmpty()) {
                    // Get the occurrence index for this content+role combination
                    val occurrenceIndex = contentRoleCounters.getOrPut(key) { 0 }
                    contentRoleCounters[key] = occurrenceIndex + 1

                    // Find an existing message at this occurrence position that hasn't been used
                    val existing = existingList.getOrNull(occurrenceIndex)
                    if (existing != null && existing.id !in usedIds) {
                        usedIds.add(existing.id)
                        msg.copy(id = existing.id)
                    } else {
                        // No matching existing message at this position, keep generated ID
                        usedIds.add(msg.id)
                        msg
                    }
                } else {
                    usedIds.add(msg.id)
                    msg
                }
            }

            _messages.value = stableMessages + messagesToAdd
        }
    }

    /**
     * Connects to the history watch WebSocket for real-time file change notifications.
     */
    private suspend fun connectHistoryWatch(conversationId: String, token: String) {
        try {
            // Get conversation details to find claudeSession and project
            val conversation = conversationApi.getConversation(conversationId)
            val claudeSession = conversation.claudeSession
            if (claudeSession.isNullOrBlank()) {
                println("ChatViewModel: No claudeSession for history watch")
                return
            }

            // Get project to find the path
            val project = projectApi.getProject(conversation.projectId)
            val encodedPath = encodeProjectPath(project.path)

            // Store for later use
            currentEncodedPath = encodedPath
            currentClaudeSession = claudeSession

            println("ChatViewModel: Connecting history watch for $encodedPath / $claudeSession")
            historyWatchClient.connect(encodedPath, claudeSession, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("ChatViewModel: Failed to connect history watch: ${e.message}")
            // Don't fail the main connection if history watch fails
        }
    }

    /**
     * Loads messages from file-based Claude history API (legacy database-based flow).
     * Uses conversationApi to get session info, then loads from filesystem.
     */
    private suspend fun loadMessages(conversationId: String) {
        try {
            // Get conversation details to find claudeSession and project
            val conversation = conversationApi.getConversation(conversationId)

            // Set conversation title from database
            _conversationTitle.value = conversation.title

            val claudeSession = conversation.claudeSession
            if (claudeSession.isNullOrBlank()) {
                // No Claude session linked - this is a new conversation
                println("ChatViewModel: No claudeSession for conversation $conversationId")
                return
            }

            // Get project to find the path
            val project = projectApi.getProject(conversation.projectId)
            val encodedPath = encodeProjectPath(project.path)

            // Store project path for delete operations
            currentProjectPath = project.path

            // Load commands now that we have the project path
            loadCommands(project.path)

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

            // Use shared processing logic
            processLoadedMessages(response.messages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 404 Not Found is expected for new conversations without history
            val isNotFound = e.message?.contains("Not found", ignoreCase = true) == true ||
                    e.message?.contains("404", ignoreCase = true) == true
            if (isNotFound) {
                println("ChatViewModel: No history found for conversation (this is normal for new chats)")

                // Clear old state for new sessions - matches behavior in loadMessagesFromFilesystem()
                mapsMutex.withLock {
                    sessionToolUses.clear()
                    sessionToolResults.clear()
                    pendingUserMessages.clear()
                    finalizedAssistantMessages.clear()
                    processedHistoryWatchTimestamps.clear()
                }
                mutex.withLock {
                    _messages.value = emptyList()
                }
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
     * Extracts content blocks from message content, preserving the order of text and tool usages.
     * Returns an ordered list of ContentBlock items (Text and Tool interleaved as they appear).
     */
    private fun parseMessageContent(content: Any?): List<ContentBlock> {
        if (content == null) return emptyList()

        val blocks = mutableListOf<ContentBlock>()

        when (content) {
            is String -> {
                val cleaned = cleanThinkingTags(content)
                if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
            }
            is JsonPrimitive -> {
                val cleaned = cleanThinkingTags(content.content)
                if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
            }
            is JsonArray -> {
                for (element in content) {
                    when (element) {
                        is JsonPrimitive -> {
                            val cleaned = cleanThinkingTags(element.content)
                            if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
                        }
                        is JsonObject -> processJsonElementToBlocks(element, blocks)
                        else -> {} // Ignore other JSON element types
                    }
                }
            }
            is JsonObject -> processJsonElementToBlocks(content, blocks)
            is List<*> -> {
                for (item in content) {
                    when (item) {
                        is String -> {
                            val cleaned = cleanThinkingTags(item)
                            if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
                        }
                        is Map<*, *> -> processMapElementToBlocks(item, blocks)
                        else -> {} // Ignore other item types
                    }
                }
            }
            else -> {
                val cleaned = cleanThinkingTags(content.toString())
                if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
            }
        }

        return blocks
    }

    /**
     * Processes a JsonObject element and adds ContentBlock to the list (preserving order).
     * tool_result blocks are handled separately via session-level matching.
     */
    private fun processJsonElementToBlocks(
        element: JsonObject,
        blocks: MutableList<ContentBlock>
    ) {
        val type = element["type"]?.jsonPrimitive?.content
        when (type) {
            "text" -> {
                element["text"]?.jsonPrimitive?.content?.let { text ->
                    val cleaned = cleanThinkingTags(text)
                    if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
                }
            }
            "tool_use" -> {
                val id = element["id"]?.jsonPrimitive?.content ?: "tool_${blocks.size}"
                val name = element["name"]?.jsonPrimitive?.content ?: "Unknown"
                val input = element["input"]
                val summary = extractToolSummary(name, input)
                blocks.add(ContentBlock.Tool(ToolUseInfo(id = id, name = name, summary = summary)))
            }
            // tool_result is handled via session-level matching, not added as a block
        }
    }

    /**
     * Processes a Map element and adds ContentBlock to the list (preserving order).
     * tool_result blocks are handled separately via session-level matching.
     */
    private fun processMapElementToBlocks(
        item: Map<*, *>,
        blocks: MutableList<ContentBlock>
    ) {
        val type = item["type"] as? String
        when (type) {
            "text" -> {
                (item["text"] as? String)?.let { text ->
                    val cleaned = cleanThinkingTags(text)
                    if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
                }
            }
            "tool_use" -> {
                val id = (item["id"] as? String) ?: "tool_${blocks.size}"
                val name = (item["name"] as? String) ?: "Unknown"
                val input = item["input"]
                val summary = extractToolSummaryFromMap(name, input)
                blocks.add(ContentBlock.Tool(ToolUseInfo(id = id, name = name, summary = summary)))
            }
            // tool_result is handled via session-level matching, not added as a block
        }
    }

    /**
     * Extracts a brief summary for a tool usage from JsonElement input.
     */
    private fun extractToolSummary(toolName: String, input: Any?): String {
        if (input == null) return ""
        if (input !is JsonObject) return input.toString().take(100)

        return when (toolName) {
            "Read" -> input["file_path"]?.jsonPrimitive?.content ?: ""
            "Edit" -> input["file_path"]?.jsonPrimitive?.content ?: ""
            "Write" -> input["file_path"]?.jsonPrimitive?.content ?: ""
            "Grep" -> {
                val pattern = input["pattern"]?.jsonPrimitive?.content ?: ""
                val path = input["path"]?.jsonPrimitive?.content
                if (path != null) "$pattern in $path" else pattern
            }
            "Glob" -> input["pattern"]?.jsonPrimitive?.content ?: ""
            "Bash" -> {
                val cmd = input["command"]?.jsonPrimitive?.content ?: ""
                cmd.take(80) + if (cmd.length > 80) "..." else ""
            }
            "Task" -> input["description"]?.jsonPrimitive?.content ?: ""
            "WebFetch" -> input["url"]?.jsonPrimitive?.content ?: ""
            "WebSearch" -> input["query"]?.jsonPrimitive?.content ?: ""
            else -> {
                input.entries.firstOrNull()?.let { (key, value) ->
                    try {
                        val v = (value as? JsonPrimitive)?.content ?: value.toString()
                        v.take(80) + if (v.length > 80) "..." else ""
                    } catch (e: Exception) { "" }
                } ?: ""
            }
        }
    }

    /**
     * Extracts a brief summary for a tool usage from Map input.
     */
    private fun extractToolSummaryFromMap(toolName: String, input: Any?): String {
        if (input == null) return ""
        if (input !is Map<*, *>) return input.toString().take(100)

        return when (toolName) {
            "Read" -> (input["file_path"] as? String) ?: ""
            "Edit" -> (input["file_path"] as? String) ?: ""
            "Write" -> (input["file_path"] as? String) ?: ""
            "Grep" -> {
                val pattern = (input["pattern"] as? String) ?: ""
                val path = input["path"] as? String
                if (path != null) "$pattern in $path" else pattern
            }
            "Glob" -> (input["pattern"] as? String) ?: ""
            "Bash" -> {
                val cmd = (input["command"] as? String) ?: ""
                cmd.take(80) + if (cmd.length > 80) "..." else ""
            }
            "Task" -> (input["description"] as? String) ?: ""
            else -> {
                input.entries.firstOrNull()?.let { (_, value) ->
                    val v = value?.toString() ?: ""
                    v.take(80) + if (v.length > 80) "..." else ""
                } ?: ""
            }
        }
    }

    /**
     * Extracts text from tool_result content.
     */
    private fun extractToolResultText(content: Any?): String {
        return when (content) {
            null -> ""
            is String -> content
            is JsonPrimitive -> content.content
            is JsonArray -> {
                content.mapNotNull { item ->
                    when (item) {
                        is JsonPrimitive -> item.content
                        is JsonObject -> {
                            val type = item["type"]?.jsonPrimitive?.content
                            if (type == "text") item["text"]?.jsonPrimitive?.content else null
                        }
                        else -> null
                    }
                }.joinToString("\n")
            }
            else -> content.toString()
        }
    }

    /**
     * Legacy function for simple text extraction (used for backwards compatibility).
     */
    private fun extractTextContent(content: Any?): String {
        return parseMessageContent(content)
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("\n\n") { it.content }
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
     * Checks if the message is a compaction/summary message (system-generated).
     * These messages are generated when Claude Code session runs out of context
     * and should not be displayed as user messages.
     */
    private fun isCompactionMessage(text: String): Boolean {
        return text.startsWith("This session is being continued from a previous conversation") ||
               text.startsWith("Please continue the conversation from where we left") ||
               text.contains("The conversation is summarized below:") ||
               text.contains("ran out of context")
    }

    /**
     * Extracts tool_use and tool_result items from message content into separate maps.
     * Used for cross-message matching of tools with their results.
     */
    private fun extractToolsFromContent(
        content: Any?,
        toolUses: MutableMap<String, ToolUseInfo>,
        toolResults: MutableMap<String, Pair<String, Boolean>>
    ) {
        when (content) {
            is JsonArray -> {
                for (element in content) {
                    if (element is JsonObject) {
                        val type = element["type"]?.jsonPrimitive?.content
                        when (type) {
                            "tool_use" -> {
                                val id = element["id"]?.jsonPrimitive?.content ?: continue
                                val name = element["name"]?.jsonPrimitive?.content ?: "Unknown"
                                val input = element["input"]
                                val summary = extractToolSummary(name, input)
                                toolUses[id] = ToolUseInfo(id = id, name = name, summary = summary)
                            }
                            "tool_result" -> {
                                val toolUseId = element["tool_use_id"]?.jsonPrimitive?.content ?: continue
                                val resultContent = element["content"]
                                val isError = element["is_error"]?.jsonPrimitive?.content == "true"
                                val result = extractToolResultText(resultContent)
                                toolResults[toolUseId] = Pair(result, isError)
                            }
                        }
                    }
                }
            }
            is List<*> -> {
                for (item in content) {
                    if (item is Map<*, *>) {
                        val type = item["type"] as? String
                        when (type) {
                            "tool_use" -> {
                                val id = (item["id"] as? String) ?: continue
                                val name = (item["name"] as? String) ?: "Unknown"
                                val input = item["input"]
                                val summary = extractToolSummaryFromMap(name, input)
                                toolUses[id] = ToolUseInfo(id = id, name = name, summary = summary)
                            }
                            "tool_result" -> {
                                val toolUseId = (item["tool_use_id"] as? String) ?: continue
                                val resultContent = item["content"]
                                val isError = item["is_error"] == true
                                val result = when (resultContent) {
                                    is String -> resultContent
                                    is List<*> -> resultContent.mapNotNull { it?.toString() }.joinToString("\n")
                                    else -> resultContent?.toString() ?: ""
                                }
                                toolResults[toolUseId] = Pair(result, isError)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if message content contains only tool_result items (no text or tool_use).
     */
    private fun hasOnlyToolResults(content: Any?): Boolean {
        var hasToolResult = false
        var hasOther = false

        when (content) {
            is JsonArray -> {
                for (element in content) {
                    if (element is JsonObject) {
                        val type = element["type"]?.jsonPrimitive?.content
                        when (type) {
                            "tool_result" -> hasToolResult = true
                            "text" -> {
                                val text = element["text"]?.jsonPrimitive?.content
                                if (!text.isNullOrBlank()) hasOther = true
                            }
                            "tool_use" -> hasOther = true
                        }
                    }
                }
            }
            is List<*> -> {
                for (item in content) {
                    if (item is Map<*, *>) {
                        val type = item["type"] as? String
                        when (type) {
                            "tool_result" -> hasToolResult = true
                            "text" -> {
                                val text = item["text"] as? String
                                if (!text.isNullOrBlank()) hasOther = true
                            }
                            "tool_use" -> hasOther = true
                        }
                    }
                }
            }
        }

        return hasToolResult && !hasOther
    }

    /**
     * Syncs messages when app returns to foreground.
     * Reloads messages from filesystem to catch any changes made while app was in background.
     */
    fun syncOnForeground() {
        val convId = currentConversationId ?: return
        val encodedPath = currentEncodedPath
        val sessionId = currentClaudeSession

        // Skip sync if streaming is active to prevent losing streaming content
        // The streaming will handle its own synchronization with the filesystem
        if (_isStreaming.value) {
            println("ChatViewModel: Skipping foreground sync - streaming is active")
            return
        }

        // Skip sync if a connect is already in progress to prevent duplicate API calls
        if (currentConnectJob?.isActive == true) {
            println("ChatViewModel: Skipping foreground sync - connect in progress")
            return
        }

        scope.launch {
            try {
                if (encodedPath != null && sessionId != null) {
                    // Reload messages from filesystem
                    println("ChatViewModel: Syncing messages on foreground for $encodedPath / $sessionId")
                    loadMessagesFromFilesystem(encodedPath, sessionId, convId, "SYNC")
                } else {
                    // Legacy flow - reload via conversation API
                    println("ChatViewModel: Syncing messages on foreground (legacy) for $convId")
                    loadMessages(convId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("ChatViewModel: Failed to sync on foreground: ${e.message}")
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
                // Disconnect both clients (HistoryWatch is now suspend, properly awaits)
                webSocketClient.disconnect()
                historyWatchClient.disconnect()

                currentConversationId = null
                currentEncodedPath = null
                currentClaudeSession = null
                currentProjectPath = null

                // Clean up HistoryWatch streaming state
                historyWatchStreamingDebounceJob?.cancel()
                historyWatchStreamingDebounceJob = null
                isStreamingFromHistoryWatch = false
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
     * If streaming is in progress, queues the message for later.
     * For draft sessions, creates the actual session on first message.
     *
     * @param content The message text to send
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return

        // For draft sessions, we need to create the session first
        if (_isDraftSession.value) {
            scope.launch {
                createSessionAndSendMessage(content)
            }
            return
        }

        if (connectionState.value != ConnectionState.Connected) {
            _error.value = "Not connected"
            return
        }

        // If streaming is in progress, queue the message (with limit check)
        if (_isStreaming.value) {
            if (_queuedMessages.value.size >= maxQueuedMessages) {
                _error.value = "Message queue is full. Please wait for current response to complete."
                return
            }
            _queuedMessages.value = _queuedMessages.value + content
            println("ChatViewModel: Queued message while streaming: ${content.take(50)}...")
            return
        }

        scope.launch {
            sendMessageInternal(content)
        }
    }

    /**
     * Creates a new session from draft mode and sends the first message.
     * This is called when user sends the first message in a draft session.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun createSessionAndSendMessage(content: String) {
        val encodedPath = currentEncodedPath ?: run {
            _error.value = "No project path available"
            return
        }

        try {
            // Generate a new session ID
            val newSessionId = kotlin.uuid.Uuid.random().toString()
            println("ChatViewModel: Creating session from draft: $newSessionId for $encodedPath")

            // Update internal state
            currentClaudeSession = newSessionId
            val newConversationId = "$newSessionId?project=$encodedPath"
            currentConversationId = newConversationId

            // Mark as no longer draft
            _isDraftSession.value = false

            // Set title based on first message (truncated)
            val title = content.take(50).let { if (content.length > 50) "$it..." else it }
            _conversationTitle.value = title

            // Get auth token and connect WebSocket
            val token = apiClient.getAuthToken() ?: run {
                _error.value = "Not authenticated"
                _isDraftSession.value = true  // Revert to draft mode
                return
            }

            // Connect WebSocket with the new session ID
            println("ChatViewModel: Connecting WebSocket for new session: $newConversationId")
            webSocketClient.connect(newConversationId, token)

            // Wait for connection to be established
            var attempts = 0
            while (connectionState.value != ConnectionState.Connected && attempts < CONNECTION_TIMEOUT_ATTEMPTS) {
                kotlinx.coroutines.delay(CONNECTION_CHECK_INTERVAL_MS)
                attempts++
                // Handle error or disconnected states - fail fast
                val currentState = connectionState.value
                if (currentState is ConnectionState.Error || currentState is ConnectionState.Disconnected) {
                    val errorMsg = if (currentState is ConnectionState.Error) {
                        "Failed to connect: ${currentState.message}"
                    } else {
                        "Connection lost"
                    }
                    _error.value = errorMsg
                    _isDraftSession.value = true  // Revert to draft mode
                    return
                }
            }

            if (connectionState.value != ConnectionState.Connected) {
                _error.value = "Connection timeout"
                _isDraftSession.value = true  // Revert to draft mode
                return
            }

            // Connect history watch for real-time updates
            println("ChatViewModel: Connecting history watch for new session: $encodedPath / $newSessionId")
            historyWatchClient.connect(encodedPath, newSessionId, token)

            // Now send the message
            sendMessageInternal(content)

            // Defer session created event until we receive first HistoryWatch message
            // (which confirms the session folder exists on disk)
            println("ChatViewModel: Session created, deferring event until first response: $newSessionId")
            pendingSessionCreatedEmit = newSessionId

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("ChatViewModel: Failed to create session from draft: ${e.message}")
            _error.value = e.toUserMessage()
            _isDraftSession.value = true  // Revert to draft mode
        }
    }

    /**
     * Clears the session created event after it has been consumed by the UI.
     */
    fun clearSessionCreatedEvent() {
        _sessionCreatedEvent.value = null
    }

    /**
     * Internal implementation to send a message.
     * Called directly or after processing from queue.
     */
    private suspend fun sendMessageInternal(content: String) {
        try {
            // Add user message to the list (marked as pending until confirmed)
            val messageId = generateMessageId()
            val userMessage = ChatMessage(
                id = messageId,
                role = MessageRole.USER,
                blocks = listOf(ContentBlock.Text(content)),
                isStreaming = false,
                isPending = true  // Mark as pending until confirmed by history watch
            )

            // Track this pending user message to prevent duplicate from history watch
            // Use normalized content hash to handle whitespace differences in serialization
            // Lock ordering: mutex (#2) → mapsMutex (#3) to prevent deadlocks
            val normalizedHash = normalizeForComparison(content).hashCode()
            println("ChatViewModel: Added pending user message (hash=$normalizedHash): ${content.take(50)}...")

            mutex.withLock {
                mapsMutex.withLock {
                    pendingUserMessages[normalizedHash] = messageId
                }
                _messages.value = _messages.value + userMessage
            }

            // Trigger scroll to bottom for the new user message (atomic update)
            _scrollToBottomSignal.update { it + 1 }

            // Send via WebSocket
            webSocketClient.sendChat(content)

            // Prepare for streaming response (use streamingMutex for consistency with other handlers)
            streamingMutex.withLock {
                _isStreaming.value = true
                _streamingContent.value = ""
                _streamingTools.value = emptyList()
                _streamingBlocks.value = emptyList()
                streamingMessageId = generateMessageId()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.toUserMessage()
            streamingMutex.withLock {
                _isStreaming.value = false
            }
        }
    }

    /**
     * Processes the next queued message, if any.
     * Called after streaming completes.
     */
    private suspend fun processNextQueuedMessage() {
        val queue = _queuedMessages.value
        if (queue.isNotEmpty()) {
            val nextMessage = queue.first()
            _queuedMessages.value = queue.drop(1)
            println("ChatViewModel: Processing queued message: ${nextMessage.take(50)}...")
            sendMessageInternal(nextMessage)
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
     * Loads available commands from the server for the current project.
     * Uses currentProjectPath if no explicit projectPath is provided.
     * Cancels any in-flight request to prevent race conditions.
     *
     * @param projectPath Path to the project for project-level commands (optional, uses currentProjectPath if empty)
     */
    fun loadCommands(projectPath: String? = null) {
        // Cancel any previous in-flight request to prevent stale data overwriting newer data
        loadCommandsJob?.cancel()
        loadCommandsJob = scope.launch {
            _commandsLoading.value = true
            try {
                // Use provided path or fall back to currentProjectPath
                val path = projectPath ?: currentProjectPath ?: ""
                val response = commandApi.listCommands(path)
                _availableCommands.value = response.builtIn + response.custom
                println("ChatViewModel: Loaded ${response.count} commands (${response.builtIn.size} builtin, ${response.custom.size} custom)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("ChatViewModel: Failed to load commands: ${e.message}")
                // Don't show error to user - just use empty list
                _availableCommands.value = emptyList()
            } finally {
                _commandsLoading.value = false
            }
        }
    }

    /**
     * Executes a slash command via the API.
     *
     * @param commandName The command name (e.g., "/help")
     * @param commandPath Optional path for custom commands
     * @param args Command arguments
     * @param onBuiltinResult Callback for builtin command result handling
     */
    fun executeCommand(
        commandName: String,
        commandPath: String? = null,
        args: List<String> = emptyList(),
        onBuiltinResult: (ExecuteCommandResponse) -> Unit = {}
    ) {
        scope.launch {
            try {
                val projectPath = currentProjectPath ?: ""
                val response = commandApi.executeCommand(
                    commandName = commandName,
                    commandPath = commandPath,
                    args = args,
                    context = ExecuteContext(projectPath = projectPath)
                )

                when (response.type) {
                    "builtin" -> handleBuiltinCommandResult(response, onBuiltinResult)
                    "custom" -> handleCustomCommandResult(response)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Command failed: ${e.message}"
            }
        }
    }

    /**
     * Handles the result of a builtin command.
     * Most builtin commands display their results as local assistant messages,
     * rather than sending them to Claude.
     */
    private fun handleBuiltinCommandResult(
        response: ExecuteCommandResponse,
        onBuiltinResult: (ExecuteCommandResponse) -> Unit
    ) {
        when (response.action) {
            "clear" -> {
                scope.launch {
                    mutex.withLock {
                        _messages.value = emptyList()
                    }
                }
            }
            // These commands display their results as local messages (not sent to Claude)
            "help", "model", "cost", "memory", "config", "status", "rewind", "compact" -> {
                val content = response.content
                    ?: response.data?.get("content")?.toString()?.removeSurrounding("\"")
                    ?: ""
                if (content.isNotEmpty()) {
                    scope.launch {
                        mutex.withLock {
                            val resultMessage = ChatMessage(
                                id = generateMessageId(),
                                role = MessageRole.ASSISTANT,
                                blocks = listOf(ContentBlock.Text(content)),
                                isStreaming = false
                            )
                            _messages.value = _messages.value + resultMessage
                        }
                    }
                }
            }
            else -> {
                // Delegate to caller for other actions that may need custom handling
                onBuiltinResult(response)
            }
        }
    }

    /**
     * Handles the result of a custom command.
     * Custom commands return content that should be sent to Claude.
     */
    private fun handleCustomCommandResult(response: ExecuteCommandResponse) {
        response.content?.let { content ->
            if (content.isNotEmpty()) {
                // Send the processed command content as a message to Claude
                sendMessage(content)
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
        // Use the stored session info for filesystem-based deletion
        val sessionId = currentClaudeSession
        val projectPath = currentProjectPath

        if (sessionId == null) {
            onError("No session selected")
            return
        }

        if (projectPath == null) {
            // Fallback: try to fetch project info using encodedPath
            val encodedPath = currentEncodedPath
            if (encodedPath == null) {
                onError("Cannot delete: project path not available")
                return
            }

            scope.launch {
                try {
                    // Fetch project to get original path
                    val project = claudeHistoryApi.getProject(encodedPath)
                    claudeHistoryApi.deleteSession(sessionId, project.path)
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
            return
        }

        scope.launch {
            try {
                claudeHistoryApi.deleteSession(sessionId, projectPath)
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
                // Synchronize streaming state changes to prevent race conditions with HistoryWatch
                streamingMutex.withLock {
                    // If we receive stream chunks, ensure streaming state is active
                    // This handles reconnection scenarios where ViewModel was recreated
                    if (!_isStreaming.value) {
                        _isStreaming.value = true
                        if (streamingMessageId == null) {
                            streamingMessageId = generateMessageId()
                        }
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
                streamingMutex.withLock {
                    _error.value = message.error ?: "Unknown error"
                    _isStreaming.value = false
                    _streamingContent.value = ""
                }
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

    /**
     * Handles events from the history watch WebSocket.
     * This allows receiving real-time updates when Claude session files change,
     * enabling the app to show messages from Claude running in terminal or other sources.
     */
    private suspend fun handleHistoryWatchEvent(event: HistoryWatchEvent) {
        when (event) {
            is HistoryWatchEvent.Connected -> {
                println("ChatViewModel: History watch connected for ${event.sessionId}")
            }

            is HistoryWatchEvent.NewMessages -> {
                // Validate event belongs to current conversation to prevent stale messages
                // from previous conversation appearing after switching chat rooms
                if (event.sessionId != currentClaudeSession || event.encodedPath != currentEncodedPath) {
                    println("ChatViewModel: Ignoring history watch event for stale session " +
                            "(event: ${event.sessionId}, current: $currentClaudeSession)")
                    return
                }

                println("ChatViewModel: Received ${event.messages.size} new messages from history watch")

                // Emit pending session created event now that we've confirmed the session folder exists
                pendingSessionCreatedEmit?.let { sessionId ->
                    if (sessionId == event.sessionId) {
                        println("ChatViewModel: First HistoryWatch message received, emitting session created event: $sessionId")
                        _sessionCreatedEvent.value = sessionId
                        pendingSessionCreatedEmit = null
                    }
                }

                // Handle queue-operation events first (terminal queued messages)
                for (claudeMsg in event.messages) {
                    if (claudeMsg.type == "queue-operation") {
                        val operation = claudeMsg.operation
                        val queueContent = claudeMsg.content ?: continue

                        when (operation) {
                            "enqueue" -> {
                                // Add to queued messages (from Claude CLI's perspective)
                                _queuedMessages.value = _queuedMessages.value + queueContent
                                println("ChatViewModel: Queued message from CLI: ${queueContent.take(50)}...")
                            }
                            "dequeue", "clear" -> {
                                // Remove from queued messages
                                val current = _queuedMessages.value
                                if (current.isNotEmpty()) {
                                    _queuedMessages.value = current.drop(1)
                                    println("ChatViewModel: Dequeued message from CLI")
                                }
                            }
                        }
                    }
                }

                // Log incoming messages for debugging (skip queue-operation)
                for ((idx, claudeMsg) in event.messages.withIndex()) {
                    val msg = claudeMsg.message ?: continue
                    val textPreview = parseMessageContent(msg.content)
                        .filterIsInstance<ContentBlock.Text>()
                        .joinToString(" ") { it.content }
                        .take(50)
                        .replace("\n", " ")
                    println("ChatViewModel: [$idx] role=${msg.role}, preview='$textPreview...'")
                }

                // Check if any message is from assistant - this means Claude is processing
                val hasAssistantMessage = event.messages.any { it.message?.role == "assistant" }
                if (hasAssistantMessage) {
                    // Synchronize streaming state changes to prevent race conditions with WebSocket
                    // Also manage debounce job inside lock to avoid reading stale isStreamingFromHistoryWatch
                    streamingMutex.withLock {
                        if (!_isStreaming.value) {
                            println("ChatViewModel: Detected assistant activity from HistoryWatch, activating streaming state")
                            _isStreaming.value = true
                            isStreamingFromHistoryWatch = true
                            if (streamingMessageId == null) {
                                streamingMessageId = generateMessageId()
                            }
                        }

                        // Reset debounce timer - if no new messages for timeout period, finalize streaming
                        // Must be inside lock to read isStreamingFromHistoryWatch safely
                        if (isStreamingFromHistoryWatch) {
                            historyWatchStreamingDebounceJob?.cancel()
                            historyWatchStreamingDebounceJob = scope.launch {
                                delay(historyWatchStreamingTimeout)
                                // Re-check with lock since state may have changed during delay
                                streamingMutex.withLock {
                                    if (_isStreaming.value && isStreamingFromHistoryWatch) {
                                        println("ChatViewModel: HistoryWatch streaming timeout - finalizing")
                                    }
                                }
                                // Finalize outside the lock to avoid holding it during message finalization
                                if (_isStreaming.value && isStreamingFromHistoryWatch) {
                                    finalizeHistoryWatchStreaming()
                                }
                            }
                        }
                    }
                }

                // First, extract tools and results from all new messages and update session maps
                val newToolUses = mutableMapOf<String, ToolUseInfo>()
                val newToolResults = mutableMapOf<String, Pair<String, Boolean>>()

                for (claudeMsg in event.messages) {
                    val msg = claudeMsg.message ?: continue
                    extractToolsFromContent(msg.content, newToolUses, newToolResults)
                }

                println("ChatViewModel: Found ${newToolUses.size} tool_uses, ${newToolResults.size} tool_results")

                // Add new tools to session maps with proper synchronization
                mapsMutex.withLock {
                    sessionToolUses.putAll(newToolUses)
                    sessionToolResults.putAll(newToolResults)

                    // Match any new tool_results with existing tool_uses
                    for ((toolId, resultPair) in sessionToolResults) {
                        val (result, isError) = resultPair
                        if (sessionToolUses.containsKey(toolId) && sessionToolUses[toolId]?.result == null) {
                            sessionToolUses[toolId] = sessionToolUses[toolId]!!.copy(result = result, isError = isError)
                        }
                    }
                }

                // If we're streaming, update streaming blocks in order (text and tools interleaved)
                // Use streamingMutex to prevent race conditions with finalizeStreamingMessage()
                //
                // IMPORTANT: When WebSocket initiated streaming (!isStreamingFromHistoryWatch),
                // we must NOT overwrite _streamingContent because WebSocket manages it directly
                // via handleIncomingMessage. HistoryWatch should only add/update tool blocks.
                // When HistoryWatch initiated streaming (isStreamingFromHistoryWatch),
                // HistoryWatch is the source of truth for all content.
                streamingMutex.withLock {
                    if (_isStreaming.value) {
                        val currentBlocks = _streamingBlocks.value.toMutableList()
                        var blocksUpdated = false

                        // Process each assistant message to extract blocks in order
                        for (claudeMsg in event.messages) {
                            val msg = claudeMsg.message ?: continue
                            if (msg.role != "assistant") continue

                            val blocks = parseMessageContent(msg.content)
                            for (block in blocks) {
                                when (block) {
                                    is ContentBlock.Text -> {
                                        // Only add text blocks if HistoryWatch initiated streaming
                                        // If WebSocket is streaming, it manages content directly
                                        if (isStreamingFromHistoryWatch) {
                                            // Check if this text is already in blocks (avoid duplicates)
                                            val exists = currentBlocks.any {
                                                it is ContentBlock.Text && it.content == block.content
                                            }
                                            if (!exists && block.content.isNotBlank()) {
                                                currentBlocks.add(block)
                                                blocksUpdated = true
                                            }
                                        }
                                    }
                                    is ContentBlock.Tool -> {
                                        // Tool blocks are always processed regardless of streaming source
                                        // Check if tool already exists, update or add
                                        val existingIndex = currentBlocks.indexOfFirst {
                                            it is ContentBlock.Tool && it.info.id == block.info.id
                                        }
                                        // Lock ordering: streamingMutex (#1) → mapsMutex (#3) is allowed
                                        val toolWithResult = mapsMutex.withLock {
                                            sessionToolUses[block.info.id]
                                        } ?: block.info
                                        if (existingIndex >= 0) {
                                            currentBlocks[existingIndex] = ContentBlock.Tool(toolWithResult)
                                        } else {
                                            currentBlocks.add(ContentBlock.Tool(toolWithResult))
                                        }
                                        blocksUpdated = true
                                    }
                                }
                            }
                        }

                        if (blocksUpdated || isStreamingFromHistoryWatch) {
                            _streamingBlocks.value = currentBlocks
                        }

                        // Only update _streamingContent if HistoryWatch initiated streaming
                        // WebSocket manages _streamingContent directly via handleIncomingMessage
                        if (isStreamingFromHistoryWatch) {
                            _streamingContent.value = currentBlocks
                                .filterIsInstance<ContentBlock.Text>()
                                .joinToString("\n\n") { it.content }
                        }

                        // Always update tools list as it can come from both sources
                        _streamingTools.value = currentBlocks
                            .filterIsInstance<ContentBlock.Tool>()
                            .map { it.info }

                        println("ChatViewModel: Updated streaming blocks: ${currentBlocks.size} blocks (historyWatch=$isStreamingFromHistoryWatch)")
                    }

                    // Update tool results in streaming blocks
                    if (_isStreaming.value && newToolResults.isNotEmpty()) {
                        val currentBlocks = _streamingBlocks.value.toMutableList()
                        for ((toolId, resultPair) in newToolResults) {
                            val (result, isError) = resultPair
                            val existingIndex = currentBlocks.indexOfFirst {
                                it is ContentBlock.Tool && it.info.id == toolId
                            }
                            if (existingIndex >= 0) {
                                val existingTool = (currentBlocks[existingIndex] as ContentBlock.Tool).info
                                currentBlocks[existingIndex] = ContentBlock.Tool(
                                    existingTool.copy(result = result, isError = isError)
                                )
                            }
                        }
                        _streamingBlocks.value = currentBlocks
                        _streamingTools.value = currentBlocks
                            .filterIsInstance<ContentBlock.Tool>()
                            .map { it.info }
                    }
                }

                // Convert ClaudeMessages to ChatMessages with matched tools
                // Filter out already processed messages based on timestamp
                // Take a snapshot of sessionToolUses for use in the map operation
                val toolUsesSnapshot = mapsMutex.withLock { sessionToolUses.toMap() }

                val newChatMessages = event.messages.mapNotNull { claudeMsg ->
                    // Skip messages we've already processed (timestamp-based dedup)
                    val timestamp = claudeMsg.timestamp?.toEpochMilliseconds() ?: 0L
                    val alreadyProcessed = mapsMutex.withLock {
                        processedHistoryWatchTimestamps.contains(timestamp)
                    }
                    if (timestamp > 0 && alreadyProcessed) {
                        return@mapNotNull null
                    }
                    // Track this timestamp as processed with bounded set size
                    if (timestamp > 0) {
                        mapsMutex.withLock {
                            processedHistoryWatchTimestamps.add(timestamp)
                            // Prevent unbounded growth: remove oldest timestamps when limit exceeded
                            if (processedHistoryWatchTimestamps.size > MAX_PROCESSED_TIMESTAMPS) {
                                val oldest = processedHistoryWatchTimestamps.minOrNull()
                                oldest?.let { processedHistoryWatchTimestamps.remove(it) }
                            }
                        }
                    }

                    val msg = claudeMsg.message ?: return@mapNotNull null
                    val role = msg.role
                    val blocks = parseMessageContent(msg.content)

                    // Update tool blocks with results from session maps (using snapshot)
                    val blocksWithResults = blocks.map { block ->
                        when (block) {
                            is ContentBlock.Tool -> {
                                val toolWithResult = toolUsesSnapshot[block.info.id]
                                if (toolWithResult != null) ContentBlock.Tool(toolWithResult) else block
                            }
                            else -> block
                        }
                    }

                    // Skip tool_result-only messages
                    if (hasOnlyToolResults(msg.content)) {
                        return@mapNotNull null
                    }

                    // Skip messages with no content blocks
                    if (blocksWithResults.isEmpty()) return@mapNotNull null

                    // Get text content for validation
                    val textContent = blocksWithResults.filterIsInstance<ContentBlock.Text>()
                        .joinToString("\n\n") { it.content }

                    // Skip compaction/summary messages (system-generated, not user content)
                    if (isCompactionMessage(textContent)) return@mapNotNull null

                    ChatMessage(
                        id = "watch_${claudeMsg.timestamp?.toEpochMilliseconds() ?: Clock.System.now().toEpochMilliseconds()}_${(0..9999).random()}",
                        role = when (role) {
                            "user" -> MessageRole.USER
                            "assistant" -> MessageRole.ASSISTANT
                            else -> return@mapNotNull null
                        },
                        blocks = blocksWithResults,
                        isStreaming = false
                    )
                }

                // Update existing messages that have tools without results
                mutex.withLock {
                    val currentMessages = _messages.value.toMutableList()
                    var messagesUpdated = false

                    // Update existing messages with newly matched tool results
                    for (i in currentMessages.indices) {
                        val msg = currentMessages[i]
                        val hasToolsWithoutResults = msg.blocks.any { block ->
                            block is ContentBlock.Tool && block.info.result == null
                        }
                        if (hasToolsWithoutResults) {
                            // Take snapshot of tool uses for updating blocks
                            val toolUsesSnap = mapsMutex.withLock { sessionToolUses.toMap() }
                            val updatedBlocks = msg.blocks.map { block ->
                                when (block) {
                                    is ContentBlock.Tool -> {
                                        val toolId = block.info.id
                                        val updatedTool = toolUsesSnap[toolId]
                                        if (updatedTool != null && updatedTool.result != null) {
                                            ContentBlock.Tool(updatedTool)
                                        } else {
                                            block
                                        }
                                    }
                                    else -> block
                                }
                            }
                            if (updatedBlocks != msg.blocks) {
                                currentMessages[i] = msg.copy(blocks = updatedBlocks)
                                messagesUpdated = true
                                println("ChatViewModel: Updated message ${msg.id} with tool results")
                            }
                        }
                    }

                    if (messagesUpdated) {
                        _messages.value = currentMessages.toList()
                    }
                }

                if (newChatMessages.isNotEmpty()) {
                    mutex.withLock {
                        val currentMessages = _messages.value.toMutableList()
                        var updated = false

                        for (newMsg in newChatMessages) {
                            // Check if this is a pending user message we already added locally
                            // Use normalized content hash to handle whitespace differences
                            val normalizedContentHash = normalizeForComparison(newMsg.content).hashCode()
                            // Lock ordering: mutex (#2) → mapsMutex (#3) is allowed
                            val pendingMsgId = if (newMsg.role == MessageRole.USER) {
                                mapsMutex.withLock { pendingUserMessages.remove(normalizedContentHash) }
                            } else null

                            if (pendingMsgId != null) {
                                // This user message was confirmed - update isPending to false
                                val pendingIndex = currentMessages.indexOfFirst { it.id == pendingMsgId }
                                if (pendingIndex >= 0) {
                                    currentMessages[pendingIndex] = currentMessages[pendingIndex].copy(isPending = false)
                                    updated = true
                                    println("ChatViewModel: Confirmed pending user message (hash=$normalizedContentHash): ${newMsg.content.take(30)}...")
                                }
                                continue
                            }

                            // For assistant messages, check if already finalized from WebSocket
                            if (newMsg.role == MessageRole.ASSISTANT) {
                                val contentHash = newMsg.content.take(200).hashCode()
                                val newMsgToolIds = newMsg.blocks.filterIsInstance<ContentBlock.Tool>()
                                    .map { it.info.id }

                                // Check if content or any tool IDs are already finalized
                                val isAlreadyFinalized = mapsMutex.withLock {
                                    finalizedAssistantMessages.containsKey(contentHash) ||
                                        newMsgToolIds.any { toolId ->
                                            finalizedAssistantMessages.containsKey(toolId.hashCode())
                                        }
                                }

                                if (isAlreadyFinalized) {
                                    println("ChatViewModel: Skipping already finalized assistant message: ${newMsg.content.take(30)}... (${newMsgToolIds.size} tools)")
                                    continue
                                }

                                // Also check if content or tools match current streaming
                                if (_isStreaming.value) {
                                    val streamingContentValue = _streamingContent.value
                                    val streamingToolIds = _streamingBlocks.value
                                        .filterIsInstance<ContentBlock.Tool>()
                                        .map { it.info.id }.toSet()

                                    // Check text content match
                                    val textMatch = streamingContentValue.isNotEmpty() &&
                                        (newMsg.content == streamingContentValue ||
                                         newMsg.content.take(100) == streamingContentValue.take(100) ||
                                         streamingContentValue.contains(newMsg.content.take(100)))

                                    // Check tool ID match
                                    val toolMatch = newMsgToolIds.isNotEmpty() &&
                                        streamingToolIds.isNotEmpty() &&
                                        newMsgToolIds.any { it in streamingToolIds }

                                    if (textMatch || toolMatch) {
                                        println("ChatViewModel: Skipping message that matches streaming: ${newMsg.content.take(30)}... (textMatch=$textMatch, toolMatch=$toolMatch)")
                                        continue
                                    }
                                }
                            }

                            // Find existing message with same content and role
                            // Use normalized content comparison to handle minor whitespace differences
                            val newMsgNormalized = newMsg.content.trim().replace(WHITESPACE_REGEX, " ")
                            val existingIndex = currentMessages.indexOfFirst { existing ->
                                if (existing.role != newMsg.role) return@indexOfFirst false

                                // Exact match
                                if (existing.content == newMsg.content) return@indexOfFirst true

                                // Normalized match (handles whitespace differences)
                                val existingNormalized = existing.content.trim().replace(WHITESPACE_REGEX, " ")
                                if (existingNormalized == newMsgNormalized) return@indexOfFirst true

                                // Prefix match for streaming scenarios where content grows
                                if (existingNormalized.isNotEmpty() && newMsgNormalized.isNotEmpty()) {
                                    val minLen = minOf(existingNormalized.length, newMsgNormalized.length, 150)
                                    if (minLen >= 50 && existingNormalized.take(minLen) == newMsgNormalized.take(minLen)) {
                                        return@indexOfFirst true
                                    }
                                }

                                false
                            }

                            if (existingIndex >= 0) {
                                // If new message has more blocks or tools, update it
                                val existing = currentMessages[existingIndex]
                                val existingToolCount = existing.blocks.count { it is ContentBlock.Tool }
                                val newToolCount = newMsg.blocks.count { it is ContentBlock.Tool }

                                if (existingToolCount == 0 && newToolCount > 0) {
                                    // New message has tools that existing doesn't, update with new blocks
                                    currentMessages[existingIndex] = existing.copy(blocks = newMsg.blocks)
                                    updated = true
                                    println("ChatViewModel: Updated existing message with $newToolCount tools")
                                } else if (newMsg.blocks.size > existing.blocks.size) {
                                    // Update with more complete message (more blocks)
                                    currentMessages[existingIndex] = existing.copy(blocks = newMsg.blocks)
                                    updated = true
                                    println("ChatViewModel: Updated message with more blocks")
                                }
                                // Otherwise skip (duplicate)
                                println("ChatViewModel: Skipping duplicate message: ${newMsg.role}, ${newMsg.content.take(30)}...")
                            } else {
                                // New message, add it
                                currentMessages.add(newMsg)
                                updated = true
                                println("ChatViewModel: Added new message from history watch: ${newMsg.role}, ${newMsg.content.take(30)}...")
                            }
                        }

                        if (updated) {
                            _messages.value = currentMessages
                        }
                    }
                }

                // Streaming completion is handled by:
                // 1. WebSocket COMPLETE message (for app-initiated messages)
                // 2. Debounce timer (for HistoryWatch-initiated streaming from terminal)
            }

            is HistoryWatchEvent.Error -> {
                println("ChatViewModel: History watch error: ${event.message}")
                // Clear pending event to prevent stale state
                pendingSessionCreatedEmit = null
            }

            is HistoryWatchEvent.Disconnected -> {
                println("ChatViewModel: History watch disconnected")
                // Clear pending event to prevent stale state
                pendingSessionCreatedEmit = null
            }
        }
    }

    private suspend fun finalizeStreamingMessage() {
        // Use streamingMutex to prevent race conditions with WebSocket/HistoryWatch handlers
        // that also modify streaming state
        streamingMutex.withLock {
            val content = _streamingContent.value
            val messageId = streamingMessageId
            val tools = _streamingTools.value
            val orderedBlocks = _streamingBlocks.value

            if ((content.isNotEmpty() || tools.isNotEmpty() || orderedBlocks.isNotEmpty()) && messageId != null) {
                // Build final blocks combining content from both sources:
                // - WebSocket manages _streamingContent (text)
                // - HistoryWatch manages _streamingBlocks (tools, and text only if isStreamingFromHistoryWatch)
                //
                // When WebSocket is streaming (!isStreamingFromHistoryWatch):
                //   - orderedBlocks may only have tool blocks (no text since we skip text in HistoryWatch)
                //   - content has the full text from WebSocket
                //   - We need to combine: text from content + tools from orderedBlocks
                //
                // When HistoryWatch is streaming (isStreamingFromHistoryWatch):
                //   - orderedBlocks has both text and tools in correct order
                //   - Use orderedBlocks directly
                val blocks = if (orderedBlocks.isNotEmpty()) {
                    val hasTextBlock = orderedBlocks.any { it is ContentBlock.Text }
                    if (!hasTextBlock && content.isNotEmpty()) {
                        // WebSocket streaming case: orderedBlocks has only tools, content has text
                        // Prepend text block, then add all tool blocks
                        listOf(ContentBlock.Text(content)) + orderedBlocks
                    } else {
                        // HistoryWatch streaming case: orderedBlocks has everything
                        orderedBlocks
                    }
                } else {
                    // No ordered blocks - use legacy behavior (text first, then tools)
                    val legacyBlocks = mutableListOf<ContentBlock>()
                    if (content.isNotEmpty()) {
                        legacyBlocks.add(ContentBlock.Text(content))
                    }
                    tools.forEach { tool ->
                        legacyBlocks.add(ContentBlock.Tool(tool))
                    }
                    legacyBlocks
                }

                val assistantMessage = ChatMessage(
                    id = messageId,
                    role = MessageRole.ASSISTANT,
                    blocks = blocks,
                    isStreaming = false
                )

                mutex.withLock {
                    // Check if this content already exists in messages (avoid duplicates from history watch)
                    // Must check both text content AND tool blocks for proper duplicate detection
                    val streamingToolIds = blocks.filterIsInstance<ContentBlock.Tool>()
                        .map { it.info.id }.toSet()

                    val isDuplicate = _messages.value.any { existing ->
                        if (existing.role != MessageRole.ASSISTANT) return@any false

                        // Check text content match
                        val textMatch = existing.content == content ||
                            (content.isNotEmpty() && existing.content.contains(content.take(100)))

                        // Check tool block match (for tool-heavy messages where text may be empty)
                        val existingToolIds = existing.blocks.filterIsInstance<ContentBlock.Tool>()
                            .map { it.info.id }.toSet()
                        val toolMatch = streamingToolIds.isNotEmpty() &&
                            existingToolIds.isNotEmpty() &&
                            streamingToolIds.intersect(existingToolIds).isNotEmpty()

                        textMatch || toolMatch
                    }

                    if (!isDuplicate) {
                        _messages.value = _messages.value + assistantMessage
                        // Track this finalized message to prevent duplicate from history watch
                        // Lock ordering: mutex (#2) → mapsMutex (#3) is allowed
                        val contentHash = content.take(200).hashCode()
                        mapsMutex.withLock {
                            finalizedAssistantMessages[contentHash] = messageId
                            // Also track tool IDs to prevent duplicates
                            streamingToolIds.forEach { toolId ->
                                finalizedAssistantMessages[toolId.hashCode()] = messageId
                            }
                        }
                        println("ChatViewModel: Finalized streaming message: ${content.take(50)}... (${streamingToolIds.size} tools)")
                    } else {
                        println("ChatViewModel: Skipping duplicate finalization: ${content.take(50)}... (${streamingToolIds.size} tools)")
                    }
                }
            }

            // Reset streaming state atomically within the same lock
            _isStreaming.value = false
            _streamingContent.value = ""
            _streamingTools.value = emptyList()
            _streamingBlocks.value = emptyList()
            streamingMessageId = null

            // Reset HistoryWatch streaming state if it was active
            isStreamingFromHistoryWatch = false
            historyWatchStreamingDebounceJob?.cancel()
            historyWatchStreamingDebounceJob = null
        }

        // Clear any remaining pending user messages as fallback
        // When streaming completes, we assume all user messages have been processed
        // Lock ordering: mutex (#2) → mapsMutex (#3) for thread-safe access
        mutex.withLock {
            // Get pending entries with mapsMutex protection
            val pendingEntries = mapsMutex.withLock {
                if (pendingUserMessages.isEmpty()) {
                    return@withLock emptyList()
                }
                pendingUserMessages.toList().also {
                    pendingUserMessages.clear()
                }
            }

            if (pendingEntries.isNotEmpty()) {
                val currentMessages = _messages.value.toMutableList()
                var updated = false
                for ((_, pendingMsgId) in pendingEntries) {
                    val index = currentMessages.indexOfFirst { it.id == pendingMsgId }
                    if (index >= 0 && currentMessages[index].isPending) {
                        currentMessages[index] = currentMessages[index].copy(isPending = false)
                        updated = true
                        println("ChatViewModel: Cleared pending flag on streaming complete: ${currentMessages[index].content.take(30)}...")
                    }
                }
                if (updated) {
                    _messages.value = currentMessages
                }
            }
        }

        // Trigger scroll to bottom signal for UI (atomic update)
        _scrollToBottomSignal.update { it + 1 }

        // Process next queued message if any (outside the lock to avoid deadlock)
        processNextQueuedMessage()
    }

    /**
     * Finalizes streaming that was initiated by HistoryWatch (not WebSocket).
     * Called when no new assistant messages arrive for a timeout period.
     */
    private suspend fun finalizeHistoryWatchStreaming() {
        // Guard: Only finalize if still in HistoryWatch streaming mode
        // This prevents race conditions with WebSocket COMPLETE message
        // Use streamingMutex for thread-safe read of isStreamingFromHistoryWatch
        val shouldFinalize = streamingMutex.withLock { isStreamingFromHistoryWatch }
        if (!shouldFinalize) {
            println("ChatViewModel: HistoryWatch streaming already finalized, skipping")
            return
        }

        println("ChatViewModel: Finalizing HistoryWatch streaming")

        // Reuse finalizeStreamingMessage() to properly save streaming blocks as a message
        // before clearing the streaming state. This prevents chat logs from disappearing.
        finalizeStreamingMessage()
    }

    private fun generateMessageId(): String {
        return "msg_${Clock.System.now().toEpochMilliseconds()}_${(0..9999).random()}"
    }
}
