package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.api.CommandApi
import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.api.QueueApi
import com.claudecode.native.data.model.QueuedMessageDto
import com.claudecode.native.data.model.Command
import com.claudecode.native.data.model.ExecuteCommandResponse
import com.claudecode.native.data.model.ExecuteContext
import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.data.model.SessionState
import com.claudecode.native.data.websocket.ConnectionState
import com.claudecode.native.data.websocket.HistoryWatchClient
import com.claudecode.native.data.websocket.HistoryWatchEvent
import com.claudecode.native.data.websocket.IncomingMessage
import com.claudecode.native.data.websocket.MessageType
import com.claudecode.native.data.websocket.ImageContentDto
import com.claudecode.native.data.websocket.QueueAddPayload
import com.claudecode.native.data.websocket.QueueRemovePayload
import com.claudecode.native.data.websocket.QueueSyncPayload
import com.claudecode.native.data.websocket.QueueMessagePayload
import com.claudecode.native.data.websocket.SessionStatePayload
import com.claudecode.native.data.websocket.TodoItemPayload
import com.claudecode.native.data.websocket.UnifiedWebSocketClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import com.claudecode.native.ui.component.ProgressStatus
import com.claudecode.native.util.DebugLogger
import com.claudecode.native.util.toUserMessage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock

/**
 * ViewModel for the chat screen, managing real-time messaging via WebSocket.
 *
 * Handles:
 * - WebSocket connection lifecycle (single unified connection per user)
 * - Message sending and receiving
 * - Streaming response accumulation
 * - Connection state exposure for UI updates
 * - Loading messages from file-based Claude history
 *
 * @param unifiedWebSocketClient Unified WebSocket client (single connection per user)
 * @param apiClient API client for auth token retrieval
 * @param conversationApi API client for conversation operations
 * @param projectApi API client for project operations
 * @param claudeHistoryApi API client for file-based Claude history
 * @param scope Injected coroutine scope for lifecycle management
 */
class ChatViewModel(
    private val unifiedWebSocketClient: UnifiedWebSocketClient,
    private val apiClient: ApiClient,
    private val conversationApi: ConversationApi,
    private val projectApi: ProjectApi,
    private val claudeHistoryApi: ClaudeHistoryApi,
    private val historyWatchClient: HistoryWatchClient,
    private val commandApi: CommandApi,
    private val queueApi: QueueApi,
    private val scope: CoroutineScope
) {
    /** JSON parser for queue WebSocket payloads */
    private val json = Json { ignoreUnknownKeys = true }

    /** Queue manager for message queue operations */
    private val queueManager = QueueManager(
        json = json,
        queueApi = queueApi,
        unifiedWebSocketClient = unifiedWebSocketClient,
        scope = scope,
        onError = { error -> _error.value = error },
        isFilesystemSession = ::isFilesystemSessionId,
        generateMessageId = ::generateMessageId
    )

    /** Progress tracker for per-conversation streaming progress */
    private val progressTracker = ProgressTracker()
    /**
     * LOCK ORDERING (always acquire in this order to prevent deadlocks):
     * 1. streamingMutex - protects streaming state (_isStreaming, isStreamingFromHistoryWatch)
     * 2. mutex - protects message list (_messages)
     * 3. mapsMutex - protects tool tracking maps (sessionToolUses, sessionToolResults)
     * 4. sessionCreatedMutex - protects pendingSessionCreatedEmit (independent, never nested)
     *
     * When multiple locks are needed, acquire in this order. Never acquire a higher-numbered
     * lock while holding a lower-numbered one.
     *
     * Note: sessionCreatedMutex (#4) is designed to be acquired independently and is never
     * nested with other locks, so it can be safely used in any context.
     */
    private val mutex = Mutex()  // Lock #2: protects _messages updates

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** Flow of chat messages in the conversation. */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    /** True when the assistant is actively streaming a response. */
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** Flow for current conversation ID to enable reactive queue filtering. */
    private val _currentConversationIdFlow = MutableStateFlow<String?>(null)

    /** Current conversation's queued messages (exposes only active conversation's queue). */
    val queuedMessages: StateFlow<List<QueuedMessage>> by lazy {
        combine(queueManager.queuedMessagesMap, _currentConversationIdFlow) { map, convId ->
            convId?.let { map[it] } ?: emptyList()
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    private val _error = MutableStateFlow<String?>(null)
    /** Current error message, if any. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _conversationTitle = MutableStateFlow<String?>(null)
    /** Current conversation title for display in UI. */
    val conversationTitle: StateFlow<String?> = _conversationTitle.asStateFlow()

    private val _isDraftSession = MutableStateFlow(false)
    /** True when this is a draft session (not yet created on server). */
    val isDraftSession: StateFlow<Boolean> = _isDraftSession.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    /** True when there are more messages to load (pagination). */
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    /** True when loading more messages (pagination in progress). */
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** Current offset for pagination (number of messages already loaded). */
    private var currentMessagesOffset: Int = 0

    /**
     * Event data for when a new session is created from draft mode.
     */
    data class SessionCreatedInfo(
        val sessionId: String,
        val encodedPath: String
    )

    private val _sessionCreatedEvent = MutableStateFlow<SessionCreatedInfo?>(null)
    /**
     * Emits session info when a draft session is converted to a real session.
     * UI can observe this to update the sidebar/project list with polling.
     */
    val sessionCreatedEvent: StateFlow<SessionCreatedInfo?> = _sessionCreatedEvent.asStateFlow()

    /**
     * Pending session info to emit when first server response arrives (STREAM message).
     * HistoryWatch serves as fallback for edge cases where STREAM may be missed.
     * Protected by sessionCreatedMutex to ensure thread-safe access from multiple handlers.
     */
    private var pendingSessionCreatedEmit: SessionCreatedInfo? = null
    private val sessionCreatedMutex = Mutex()


    private val _attachedImages = MutableStateFlow<List<AttachedImage>>(emptyList())
    /** Images attached to the current message before sending. */
    val attachedImages: StateFlow<List<AttachedImage>> = _attachedImages.asStateFlow()

    /**
     * Adds an image to the attachment list.
     * Each image is assigned a unique ID for tracking.
     *
     * @param data Raw image bytes
     * @param mediaType MIME type of the image (e.g., "image/png")
     * @param fileName Optional filename for display
     */
    @OptIn(ExperimentalUuidApi::class)
    fun addAttachedImage(data: ByteArray, mediaType: String, fileName: String? = null) {
        val image = AttachedImage(
            id = kotlin.uuid.Uuid.random().toString(),
            data = data,
            mediaType = mediaType,
            fileName = fileName
        )
        _attachedImages.update { it + image }
    }

    /**
     * Removes an image from the attachment list by ID.
     *
     * @param imageId The unique ID of the image to remove
     */
    fun removeAttachedImage(imageId: String) {
        _attachedImages.update { images -> images.filter { it.id != imageId } }
    }

    /**
     * Clears all attached images.
     */
    fun clearAttachedImages() {
        _attachedImages.value = emptyList()
    }

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

    /**
     * Progress status for the current conversation's status line UI (Claude Code style).
     * Tracks elapsed time, status text, tokens, and thinking time during streaming.
     * Reactively switches to the correct StateFlow when conversation changes.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val progressStatus: StateFlow<ProgressStatus> by lazy {
        _currentConversationIdFlow.flatMapLatest { convId ->
            convId?.let {
                progressTracker.getProgressStatus(it)
            } ?: flowOf(ProgressStatus())
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), ProgressStatus())
    }

    /** Connection state exposed from the unified WebSocket client. */
    val connectionState: StateFlow<ConnectionState>
        get() = unifiedWebSocketClient.connectionState

    private var currentConversationId: String? = null
    private var currentEncodedPath: String? = null
    private var currentClaudeSession: String? = null
    private var currentProjectPath: String? = null  // Original project path for API calls

    private var loadCommandsJob: Job? = null
    private var currentConnectJob: Job? = null  // Track current connect job to cancel on room switch
    private var isStreamingFromHistoryWatch = false

    // Session-level tool tracking for matching tool_use with tool_result across messages
    // Using mutableMapOf with Mutex for thread safety across WebSocket and HistoryWatch handlers
    // (ConcurrentHashMap is not available in Kotlin Multiplatform)
    private val sessionToolUses = mutableMapOf<String, ToolUseInfo>()
    private val sessionToolResults = mutableMapOf<String, Pair<String, Boolean>>()

    // Track pending user messages to properly handle duplicates from history watch
    // Key: content hash, Value: message ID
    private val pendingUserMessages = mutableMapOf<Int, String>()
    companion object {
        private const val TAG = "ChatViewModel"
        /** Marker for draft sessions that haven't been created yet. */
        const val DRAFT_SESSION_MARKER = "draft"
        /** Timeout for WebSocket connection establishment in milliseconds. */
        private const val CONNECTION_TIMEOUT_MS = 5000L
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Regex to match @ file mentions at the start of content.
         * Claude CLI prepends @/path/to/image.png to messages with attachments.
         * Format: @/path/to/file followed by space, repeated at start of string.
         */
        private val AT_MENTION_REGEX = Regex("""^(@\S+\s+)+""")

        /**
         * Normalizes content for hash comparison.
         * - Strips @ file mentions from the beginning (added by Claude CLI for images)
         * - Trims whitespace and normalizes internal whitespace to single spaces
         * This ensures hash comparison works even when content is modified
         * during serialization/deserialization (e.g., trailing spaces removed).
         */
        fun normalizeForComparison(content: String): String {
            // Strip @ mentions from beginning (e.g., "@/tmp/claude-image-xxx.png message")
            val withoutMentions = content.replace(AT_MENTION_REGEX, "")
            return withoutMentions.trim().replace(WHITESPACE_REGEX, " ")
        }

        /**
         * Checks if the given conversation ID represents a filesystem-based session.
         * Filesystem sessions use "sessionId?project=encodedPath" format.
         *
         * @param conversationId The conversation ID to check
         * @return true if this is a filesystem-based session ID, false otherwise
         */
        fun isFilesystemSessionId(conversationId: String): Boolean {
            return conversationId.contains("?project=")
        }
    }

    private val mapsMutex = Mutex()  // Lock #3: protects sessionToolUses, sessionToolResults

    private val streamingMutex = Mutex()  // Lock #1: protects streaming state (see lock ordering above)

    /**
     * Clears all session state for switching conversations or starting fresh.
     */
    private suspend fun clearSessionState() {
        mapsMutex.withLock {
            sessionToolUses.clear()
            sessionToolResults.clear()
            pendingUserMessages.clear()
        }
        mutex.withLock {
            _messages.value = emptyList()
        }
        // Reset pagination state
        currentMessagesOffset = 0
        _hasMoreMessages.value = false
        _isLoadingMore.value = false
    }

    /**
     * Loads more (older) messages for pagination.
     * Called when user scrolls to the top of the message list.
     */
    fun loadMoreMessages() {
        // Guard: Don't load if already loading or no more messages
        if (_isLoadingMore.value || !_hasMoreMessages.value) {
            DebugLogger.d(TAG, "loadMoreMessages skipped - isLoading=${_isLoadingMore.value}, hasMore=${_hasMoreMessages.value}")
            return
        }

        val encodedPath = currentEncodedPath ?: return
        val sessionId = currentClaudeSession ?: return
        val expectedConversationId = currentConversationId ?: return

        scope.launch {
            _isLoadingMore.value = true
            try {
                DebugLogger.d(TAG, "Loading more messages, offset=$currentMessagesOffset")
                val response = claudeHistoryApi.getSessionMessages(
                    encodedPath = encodedPath,
                    sessionId = sessionId,
                    limit = 100,
                    offset = currentMessagesOffset,
                    summary = false
                )

                // GUARD CHECK: Verify we're still in the same conversation
                if (currentConversationId != expectedConversationId) {
                    DebugLogger.d(TAG, "Room switched during loadMore, discarding results")
                    return@launch
                }

                if (response.messages.isEmpty()) {
                    _hasMoreMessages.value = false
                    return@launch
                }

                DebugLogger.d(TAG, "Got ${response.messages.size} more messages, hasMore=${response.hasMore}")

                // Update pagination state
                currentMessagesOffset += response.messages.size
                _hasMoreMessages.value = response.hasMore

                // Process and prepend older messages
                processOlderMessages(response.messages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Failed to load more messages: ${e.message}", e)
                _error.value = e.toUserMessage()
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Processes older messages and prepends them to the existing message list.
     * Uses same logic as processLoadedMessages for consistency.
     */
    private suspend fun processOlderMessages(messages: List<com.claudecode.native.data.model.ClaudeMessage>) {
        // Build tool maps for older messages
        val olderToolUses = mutableMapOf<String, ToolUseInfo>()
        val olderToolResults = mutableMapOf<String, Pair<String, Boolean>>()

        for (msg in messages) {
            val message = msg.message ?: continue
            extractToolsFromContent(message.content, olderToolUses, olderToolResults)
        }

        // Match tool results to tool uses (using immutable copy pattern)
        for ((toolId, resultPair) in olderToolResults) {
            val (result, isError) = resultPair
            if (olderToolUses.containsKey(toolId)) {
                olderToolUses[toolId] = olderToolUses[toolId]!!.copy(result = result, isError = isError)
            }
        }

        // Merge older tool uses into session maps
        mapsMutex.withLock {
            sessionToolUses.putAll(olderToolUses)
            sessionToolResults.putAll(olderToolResults)
        }

        // Convert older messages to ChatMessages (using same pattern as processLoadedMessages)
        val olderChatMessages = messages.mapIndexedNotNull { index, msg ->
            val message = msg.message ?: return@mapIndexedNotNull null
            val role = message.role
            val blocks = parseMessageContent(message.content, msg.uuid)

            // Update tool blocks with results
            val blocksWithResults = updateBlocksWithToolResults(blocks, olderToolUses)

            // Skip tool_result-only messages
            if (hasOnlyToolResults(message.content)) {
                return@mapIndexedNotNull null
            }

            // Skip meta messages
            if (msg.isMeta) {
                return@mapIndexedNotNull null
            }

            // Skip messages with no content blocks
            if (blocksWithResults.isEmpty()) return@mapIndexedNotNull null

            // Skip compaction/summary messages
            val textContent = blocksWithResults.filterIsInstance<ContentBlock.Text>()
                .joinToString("\n\n") { it.content }
            if (isCompactionMessage(textContent)) return@mapIndexedNotNull null

            ChatMessage(
                id = msg.uuid ?: "msg_${msg.timestamp?.toEpochMilliseconds() ?: index}",
                role = when (role) {
                    "user" -> MessageRole.USER
                    "assistant" -> MessageRole.ASSISTANT
                    else -> return@mapIndexedNotNull null
                },
                blocks = blocksWithResults,
                isStreaming = false,
                gitBranch = msg.gitBranch,
                agentId = msg.agentId,
                isSidechain = msg.isSidechain
            )
        }

        // Prepend older messages to existing list, filtering out duplicates
        mutex.withLock {
            val currentMessages = _messages.value
            val existingIds = currentMessages.map { it.id }.toSet()
            val uniqueOlderMessages = olderChatMessages.filter { it.id !in existingIds }
            _messages.value = uniqueOlderMessages + currentMessages
            DebugLogger.d(TAG, "Prepended ${uniqueOlderMessages.size} older messages (filtered ${olderChatMessages.size - uniqueOlderMessages.size} duplicates)")
        }
    }

    /**
     * Updates tool blocks with results from the tool uses map.
     * Used during message loading and history watch updates.
     */
    private fun updateBlocksWithToolResults(
        blocks: List<ContentBlock>,
        toolUses: Map<String, ToolUseInfo>
    ): List<ContentBlock> {
        return blocks.map { block ->
            when (block) {
                is ContentBlock.Tool -> {
                    val toolWithResult = toolUses[block.info.id]
                    if (toolWithResult != null) ContentBlock.Tool(toolWithResult) else block
                }
                else -> block
            }
        }
    }


    init {
        // Collect incoming WebSocket messages from unified client
        scope.launch {
            unifiedWebSocketClient.messages.collect { message ->
                handleIncomingMessage(message)
            }
        }

        // Collect session state updates from unified WebSocket client
        scope.launch {
            unifiedWebSocketClient.sessionState.collect { state ->
                state?.let { handleSessionState(it) }
            }
        }

        // Collect history watch events for real-time file changes
        scope.launch {
            historyWatchClient.events.collect { event ->
                handleHistoryWatchEvent(event)
            }
        }

        // Sync queue and retry local messages when reconnected
        scope.launch {
            var previousState: ConnectionState? = null
            unifiedWebSocketClient.connectionState.collect { newState ->
                // Check if we just reconnected (transition to Connected from non-Connected state)
                if (newState == ConnectionState.Connected && previousState != ConnectionState.Connected) {
                    // Server will send queue_sync via WebSocket, but also sync local messages
                    queueManager.syncLocalMessagesToServer(_currentConversationIdFlow.value)
                    queueManager.retryQueuedMessages(_currentConversationIdFlow.value)
                }
                previousState = newState
            }
        }
    }

    /**
     * Handles session state updates from the unified WebSocket client.
     * Updates streaming state, todos, and queue based on server-synchronized state.
     */
    private fun handleSessionState(state: SessionStatePayload) {
        scope.launch {
            DebugLogger.d(TAG, "Received session state for: ${state.conversationId}, streaming=${state.isStreaming}")

            // Only process if this is for the current conversation
            if (state.conversationId != currentConversationId?.split("?")?.firstOrNull()) {
                DebugLogger.d(TAG, "Session state for different conversation, ignoring")
                return@launch
            }

            // Update streaming state
            streamingMutex.withLock {
                _isStreaming.value = state.isStreaming
            }

            // Update todos from session state
            if (!state.todos.isNullOrEmpty()) {
                updateProgressFromTodos(state.todos!!)
            }

            // Update queue from session state (sync with server)
            if (!state.queue.isNullOrEmpty()) {
                val convId = _currentConversationIdFlow.value ?: return@launch
                val serverQueue = state.queue!!.map { msg ->
                    QueuedMessage(
                        id = msg.id,
                        content = msg.content,
                        queuedAt = msg.queuedAt,
                        source = QueuedMessageSource.SERVER
                    )
                }
                // FIX: Atomic read-modify-write to prevent race condition
                // Reading currentQueue INSIDE the update lambda ensures we get
                // the latest value and don't lose concurrent updates
                queueManager.updateCurrentQueue(convId) { queue ->
                    val localOnly = queue.filter { it.source == QueuedMessageSource.LOCAL }
                    serverQueue + localOnly
                }
            }
        }
    }

    /**
     * Updates progress status from todo list in session state.
     */
    private fun updateProgressFromTodos(todos: List<TodoItemPayload>) {
        val convId = currentConversationId ?: return

        // Convert TodoItemPayload to TodoItem
        val todoItems = todos.map { payload ->
            com.claudecode.native.data.model.TodoItem(
                content = payload.content,
                status = payload.status,
                activeForm = payload.activeForm,
                priority = payload.priority,
                id = payload.id
            )
        }

        scope.launch {
            progressTracker.updateTodos(convId, todoItems)

            // Update status text based on in-progress todo
            val currentTodo = todos.find { it.status == "in_progress" }
            if (currentTodo?.activeForm != null) {
                progressTracker.updateStatusText(convId, currentTodo.activeForm)
            } else if (todos.isNotEmpty()) {
                progressTracker.updateStatusText(convId, "Processing...")
            }
        }
    }

    /**
     * Syncs local-only messages to the server after reconnection.
     * Processes messages sequentially to avoid race conditions with WebSocket queue_sync.
     *
     * Note: Skipped for filesystem-based sessions which don't have database conversation IDs.
     */

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
            DebugLogger.d(TAG, "[$connectCallId] SKIP: Already connected to $shortConvId")
            return
        }

        DebugLogger.d(TAG, "[$connectCallId] >>> connect() CALLED with: $shortConvId")
        DebugLogger.d(TAG, "[$connectCallId] Current state: currentConversationId=${currentConversationId?.take(20)}, title=${_conversationTitle.value?.take(30)}")

        // Cancel any previous connect job to prevent race conditions when switching rooms quickly
        val prevJob = currentConnectJob
        if (prevJob != null) {
            DebugLogger.d(TAG, "[$connectCallId] Cancelling previous connect job")
            prevJob.cancel()
        }

        currentConnectJob = scope.launch {
            try {
                DebugLogger.d(TAG, "[$connectCallId] Inside coroutine, checking if need to disconnect")
                // Disconnect from previous conversation if any
                if (currentConversationId != null && currentConversationId != conversationId) {
                    // Unsubscribe from current conversation (unified connection stays open)
                    unifiedWebSocketClient.unsubscribe()
                    historyWatchClient.disconnect()
                    isStreamingFromHistoryWatch = false

                    // NOTE: We intentionally DO NOT clear messages/title here anymore.
                    // Old messages remain visible during loading to prevent empty screen flash.
                    // State will be replaced atomically when new data loads successfully.
                    // If guard check fails (user switched rooms), we abort without clearing.

                    // Only clear transient streaming state (queue is preserved per conversation)
                    _isStreaming.value = false
                    _error.value = null
                    _conversationTitle.value = null
                    _isDraftSession.value = false
                    _sessionCreatedEvent.value = null

                    // Clear progress status for previous conversation to prevent stale Processing state
                    // This is important for draft sessions which share the same conversationId pattern
                    currentConversationId?.let { clearProgressStateForConversation(it) }
                }

                // Set conversation ID immediately so subsequent connect() calls know to disconnect
                currentConversationId = conversationId
                _currentConversationIdFlow.value = conversationId
                DebugLogger.d(TAG, "[$connectCallId] Set currentConversationId = $shortConvId")

                val token = apiClient.getAuthToken() ?: run {
                    _error.value = "Not authenticated"
                    return@launch
                }

                // Parse conversationId to extract session info
                // Format: "draft?project=encodedPath", "sessionId?project=encodedPath", or legacy UUID
                val (sessionId, encodedPath) = parseConversationId(conversationId)
                DebugLogger.d(TAG, "[$connectCallId] Parsed: sessionId=${sessionId?.take(15)}, encodedPath=${encodedPath?.take(30)}")

                if (sessionId != null && encodedPath != null) {
                    // Check if this is a draft session (not yet created)
                    if (sessionId == DRAFT_SESSION_MARKER) {
                        DebugLogger.d(TAG, "ChatViewModel: Draft session mode for $encodedPath")
                        _isDraftSession.value = true
                        currentEncodedPath = encodedPath
                        currentClaudeSession = null  // No session ID yet
                        _conversationTitle.value = "New Chat"

                        // FIX: Clear previous session's messages for draft sessions
                        clearSessionState()
                        DebugLogger.d(TAG, "ChatViewModel: Cleared previous session state for draft")

                        // FIX: Clear progress status for this conversation ID to ensure fresh state
                        // Draft sessions reuse the same conversationId pattern, so old state must be cleared
                        clearProgressStateForConversation(conversationId)

                        // Fetch project info for project path and commands
                        try {
                            val project = claudeHistoryApi.getProject(encodedPath)
                            currentProjectPath = project.path
                            loadCommands(project.path)
                        } catch (e: Exception) {
                            DebugLogger.d(TAG, "ChatViewModel: Failed to fetch project info for draft: ${e.message}")
                        }

                        // Connect WebSocket in draft mode (will be ready when session is created)
                        // The WebSocket will be reconnected with actual session ID when first message is sent
                        DebugLogger.d(TAG, "ChatViewModel: Draft mode - WebSocket will connect on first message")
                        return@launch  // Don't connect WebSocket yet for draft sessions
                    }

                    // New filesystem-based flow (existing session)
                    currentEncodedPath = encodedPath
                    currentClaudeSession = sessionId

                    DebugLogger.d(TAG, "[$connectCallId] BEFORE loadMessagesFromFilesystem, title=${_conversationTitle.value?.take(30)}")

                    // Load messages directly from filesystem API
                    loadMessagesFromFilesystem(encodedPath, sessionId, conversationId, connectCallId)

                    DebugLogger.d(TAG, "[$connectCallId] AFTER loadMessagesFromFilesystem, title=${_conversationTitle.value?.take(30)}")
                    DebugLogger.d(TAG, "[$connectCallId] currentConversationId now = ${currentConversationId?.take(20)}")

                    // Guard: Check if we're still the active conversation after async load
                    // If user switched rooms during loading, abort this connection
                    if (currentConversationId != conversationId) {
                        DebugLogger.d(TAG, "[$connectCallId] !!! GUARD TRIGGERED: room switched during load, ABORTING")
                        DebugLogger.d(TAG, "[$connectCallId] BUT TITLE IS ALREADY SET TO: ${_conversationTitle.value?.take(50)}")
                        return@launch
                    }
                    DebugLogger.d(TAG, "[$connectCallId] Guard passed, continuing with connection")

                    // Connect to history watch for real-time file changes
                    DebugLogger.d(TAG, "ChatViewModel: Connecting history watch for $encodedPath / $sessionId")
                    historyWatchClient.connect(encodedPath, sessionId, token)

                    // Connect WebSocket with the full session identifier
                    // This allows continuing the conversation
                    DebugLogger.d(TAG, "[$connectCallId] Connecting WebSocket for filesystem session")
                    connectUnified(token, conversationId, sessionId, encodedPath)
                    DebugLogger.d(TAG, "[$connectCallId] <<< connect() COMPLETE for: $shortConvId")
                    DebugLogger.d(TAG, "[$connectCallId] Final state: title=${_conversationTitle.value?.take(40)}, msgCount=${_messages.value.size}")
                } else {
                    // Legacy database-based flow (fallback)
                    loadMessages(conversationId)

                    // Guard: Check if we're still the active conversation after async load
                    if (currentConversationId != conversationId) {
                        DebugLogger.d(TAG, "[$connectCallId] !!! Legacy GUARD TRIGGERED: room switched during message load")
                        return@launch
                    }

                    connectUnified(token, conversationId, null, null)
                    connectHistoryWatch(conversationId, token)
                    DebugLogger.d(TAG, "[$connectCallId] <<< Legacy connect() COMPLETE")
                }
            } catch (e: CancellationException) {
                DebugLogger.d(TAG, "[$connectCallId] !!! CANCELLED - job was cancelled")
                throw e
            } catch (e: Exception) {
                DebugLogger.d(TAG, "[$connectCallId] !!! ERROR: ${e.message}")
                _error.value = e.toUserMessage()
            }
        }
    }

    /**
     * Parses the conversationId to extract session ID and encoded path.
     * @return Pair of (sessionId, encodedPath) or (null, null) for legacy format
     */
    private fun parseConversationId(conversationId: String): Pair<String?, String?> {
        if (!isFilesystemSessionId(conversationId)) {
            return Pair(null, null)
        }
        val parts = conversationId.split("?project=")
        if (parts.size != 2) {
            return Pair(null, null)
        }
        return Pair(parts[0], parts[1])
    }

    /**
     * Connects to the unified WebSocket and subscribes to a conversation.
     * The unified client maintains a single connection per user session.
     *
     * @param token Authentication token
     * @param conversationId The full conversation ID (used for subscription)
     * @param sessionId Optional session ID for filesystem-based sessions
     * @param encodedPath Optional encoded project path
     */
    private suspend fun connectUnified(
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
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("Failed to establish unified WebSocket connection")
            }
        }

        // Subscribe to the conversation
        DebugLogger.d(TAG, "connectUnified: Subscribing to conversation...")
        unifiedWebSocketClient.subscribe(conversationId, sessionId, encodedPath)
        DebugLogger.d(TAG, "connectUnified: Subscribed successfully")
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
            DebugLogger.d(TAG, "[$callId] loadMessagesFromFilesystem START - sessionId=${sessionId.take(15)}")
            DebugLogger.d(TAG, "[$callId] Before API call, currentConversationId=${currentConversationId?.take(20)}")

            // Fetch project to get the original path and session title for delete operations
            try {
                val project = claudeHistoryApi.getProject(encodedPath)

                // GUARD CHECK: Before setting ANY state, verify we're still the active conversation
                // This prevents race condition where user clicks another room during API call
                if (currentConversationId != expectedConversationId) {
                    DebugLogger.d(TAG, "[$callId] !!! GUARD: Room switched during project fetch, aborting state update")
                    DebugLogger.d(TAG, "[$callId] Expected: ${expectedConversationId.take(20)}, Current: ${currentConversationId?.take(20)}")
                    return
                }

                currentProjectPath = project.path
                DebugLogger.d(TAG, "[$callId] Got project: ${project.name}")

                // Find the session and extract the title (firstMessage)
                val session = project.sessions.find { it.id == sessionId }
                val newTitle = if (session != null && session.firstMessage.isNotBlank()) {
                    session.firstMessage
                } else {
                    project.name
                }

                // GUARD CHECK again before setting title (in case of context switch)
                if (currentConversationId != expectedConversationId) {
                    DebugLogger.d(TAG, "[$callId] !!! GUARD: Room switched before title set, aborting")
                    return
                }

                DebugLogger.d(TAG, "[$callId] About to set title to: ${newTitle.take(50)}")
                DebugLogger.d(TAG, "[$callId] currentConversationId at title set time: ${currentConversationId?.take(20)}")
                _conversationTitle.value = newTitle
                DebugLogger.d(TAG, "[$callId] Title IS NOW: ${_conversationTitle.value?.take(50)}")

                // Load commands now that we have the project path
                loadCommands(project.path)
            } catch (e: Exception) {
                DebugLogger.d(TAG, "[$callId] Failed to fetch project info: ${e.message}")
                // Continue without project path - delete will try to decode
                _conversationTitle.value = null
            }

            // GUARD CHECK before loading messages
            if (currentConversationId != expectedConversationId) {
                DebugLogger.d(TAG, "[$callId] !!! GUARD: Room switched before message load, aborting")
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
                    DebugLogger.d(TAG, "[$callId] !!! GUARD: Room switched during message fetch, discarding ${response.messages.size} messages")
                    return
                }

                DebugLogger.d(TAG, "[$callId] Got ${response.messages.size} messages from API, hasMore=${response.hasMore}, processing...")

                // Only clear state on initial load, not on foreground sync
                // During sync, we want to preserve pending messages that are still being sent
                if (callId != "SYNC") {
                    clearSessionState()
                }

                // Update pagination state
                currentMessagesOffset = response.messages.size
                _hasMoreMessages.value = response.hasMore

                // Process messages using existing logic
                processLoadedMessages(response.messages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 404 Not Found or 400 Bad Request is expected for new sessions without history
                // This is normal - the session file doesn't exist yet or session ID is for a new draft
                val isExpectedError = e.message?.contains("Not found", ignoreCase = true) == true ||
                        e.message?.contains("404", ignoreCase = true) == true ||
                        e.message?.contains("resource not found", ignoreCase = true) == true ||
                        e.message?.contains("Bad Request", ignoreCase = true) == true ||
                        e.message?.contains("400", ignoreCase = true) == true ||
                        e.message?.contains("session not found", ignoreCase = true) == true
                if (isExpectedError) {
                    DebugLogger.d(TAG, "[$callId] No history found for session (this is normal for new chats)")

                    // GUARD CHECK: Verify we're still the active conversation before clearing state
                    if (currentConversationId != expectedConversationId) {
                        DebugLogger.d(TAG, "[$callId] !!! GUARD: Room switched during 404 handling, aborting state clear")
                        return
                    }

                    // Clear old state for new sessions
                    clearSessionState()
                    DebugLogger.d(TAG, "[$callId] Cleared state for new session")
                } else {
                    DebugLogger.d(TAG, "[$callId] Failed to load messages from filesystem: ${e.message}")
                    _error.value = e.toUserMessage()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Outer catch for project fetch errors
            DebugLogger.d(TAG, "ChatViewModel: Failed during filesystem message loading: ${e.message}")
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
            val blocks = parseMessageContent(message.content, msg.uuid)

            // Update tool blocks with results from session maps
            val blocksWithResults = updateBlocksWithToolResults(blocks, allToolUses)

            // Skip tool_result-only messages (they're matched to tool_use messages)
            if (hasOnlyToolResults(message.content)) {
                return@mapIndexedNotNull null
            }

            // Skip meta messages (skill content injected by Claude Code, not user-typed)
            if (msg.isMeta) {
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
                // Use UUID from Claude message, fallback to timestamp-based ID
                // Use same fallback pattern as HistoryWatch for consistency
                id = msg.uuid ?: "msg_${msg.timestamp?.toEpochMilliseconds() ?: index}",
                role = when (role) {
                    "user" -> MessageRole.USER
                    "assistant" -> MessageRole.ASSISTANT
                    else -> return@mapIndexedNotNull null
                },
                blocks = blocksWithResults,
                isStreaming = false,
                gitBranch = msg.gitBranch,
                agentId = msg.agentId,
                isSidechain = msg.isSidechain
            )
        }
        DebugLogger.d(TAG, "ChatViewModel: Converted to ${chatMessages.size} chat messages")

        mutex.withLock {
            val currentMessages = _messages.value
            // Preserve pending messages that haven't been confirmed by history watch yet
            // These are locally added messages waiting for filesystem sync
            val pendingMessages = currentMessages.filter { it.isPending }

            val messagesToAdd = mutableListOf<ChatMessage>()

            if (pendingMessages.isNotEmpty()) {
                DebugLogger.d(TAG, "ChatViewModel: Preserving ${pendingMessages.size} pending messages during reload")
                // Merge: loaded messages + pending messages not already in loaded list
                val loadedContentHashes = chatMessages.map { it.content.hashCode() }.toSet()
                val uniquePendingMessages = pendingMessages.filter { pending ->
                    pending.content.hashCode() !in loadedContentHashes
                }
                messagesToAdd.addAll(uniquePendingMessages)
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

            // Final deduplication by ID - O(n) using LinkedHashMap to preserve order
            val allMessages = stableMessages + messagesToAdd
            val deduplicatedMessages = allMessages
                .associateBy { it.id }  // Keeps last occurrence, preserves insertion order
                .values
                .toList()

            _messages.value = deduplicatedMessages
            DebugLogger.d(TAG, "ChatViewModel:697 - Added ${messagesToAdd.size} messages (initial load), total=${_messages.value.size}")
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
                DebugLogger.d(TAG, "ChatViewModel: No claudeSession for history watch")
                return
            }

            // Get project to find the path
            val project = projectApi.getProject(conversation.projectId)
            val encodedPath = encodeProjectPath(project.path)

            // Store for later use
            currentEncodedPath = encodedPath
            currentClaudeSession = claudeSession

            DebugLogger.d(TAG, "ChatViewModel: Connecting history watch for $encodedPath / $claudeSession")
            historyWatchClient.connect(encodedPath, claudeSession, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.d(TAG, "ChatViewModel: Failed to connect history watch: ${e.message}")
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
                DebugLogger.d(TAG, "ChatViewModel: No claudeSession for conversation $conversationId")
                return
            }

            // Get project to find the path
            val project = projectApi.getProject(conversation.projectId)
            val encodedPath = encodeProjectPath(project.path)

            // Store project path for delete operations
            currentProjectPath = project.path

            // Load commands now that we have the project path
            loadCommands(project.path)

            DebugLogger.d(TAG, "ChatViewModel: Loading messages from $encodedPath / $claudeSession")

            // Load messages from file-based API (with summary=false for full content)
            val response = claudeHistoryApi.getSessionMessages(
                encodedPath = encodedPath,
                sessionId = claudeSession,
                limit = 100,
                offset = 0,
                summary = false
            )
            DebugLogger.d(TAG, "ChatViewModel: Got ${response.messages.size} messages from API")

            // Use shared processing logic
            processLoadedMessages(response.messages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 404 Not Found is expected for new conversations without history
            val isNotFound = e.message?.contains("Not found", ignoreCase = true) == true ||
                    e.message?.contains("404", ignoreCase = true) == true
            if (isNotFound) {
                DebugLogger.d(TAG, "ChatViewModel: No history found for conversation (this is normal for new chats)")

                // Clear old state for new sessions
                clearSessionState()
            } else {
                DebugLogger.d(TAG, "ChatViewModel: Failed to load messages: ${e.message}")
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

    // Message parsing delegated to MessageParser utility object
    private fun parseMessageContent(content: Any?, messageUuid: String? = null) = MessageParser.parseMessageContent(content, messageUuid)
    private fun extractToolsFromContent(
        content: Any?,
        toolUses: MutableMap<String, ToolUseInfo>,
        toolResults: MutableMap<String, Pair<String, Boolean>>
    ) = MessageParser.extractToolsFromContent(content, toolUses, toolResults)
    private fun hasOnlyToolResults(content: Any?) = MessageParser.hasOnlyToolResults(content)
    private fun isCompactionMessage(text: String) = MessageParser.isCompactionMessage(text)

    /**
     * Syncs messages and state when app returns to foreground.
     * Reloads messages from filesystem and syncs state via REST to catch any changes
     * made while app was in background (e.g., streaming started/stopped, todos updated).
     */
    fun syncOnForeground() {
        val convId = currentConversationId ?: return
        val encodedPath = currentEncodedPath
        val sessionId = currentClaudeSession

        // Skip sync if a connect is already in progress to prevent duplicate API calls
        if (currentConnectJob?.isActive == true) {
            DebugLogger.d(TAG, "ChatViewModel: Skipping foreground sync - connect in progress")
            return
        }

        // Skip sync for draft sessions (new chats) - they don't have server-side state yet
        if (_isDraftSession.value) {
            DebugLogger.d(TAG, "ChatViewModel: Skipping foreground sync - draft session")
            return
        }

        scope.launch {
            try {
                // Always sync state first via REST to detect streaming status changes
                // This is important because streaming might have started/stopped while in background
                if (encodedPath != null && sessionId != null) {
                    DebugLogger.d(TAG, "ChatViewModel: Syncing state on foreground for $encodedPath / $sessionId")
                    try {
                        val stateResponse = claudeHistoryApi.getSessionState(encodedPath, sessionId)

                        // Update streaming state
                        streamingMutex.withLock {
                            if (_isStreaming.value != stateResponse.isStreaming) {
                                DebugLogger.d(TAG, "ChatViewModel: Foreground sync - updating streaming state: ${stateResponse.isStreaming}")
                                _isStreaming.value = stateResponse.isStreaming
                            }
                        }

                        // Update progress tracking based on REST state
                        // This is separate from streaming state to ensure progress is always synced
                        if (stateResponse.isStreaming) {
                            if (!progressTracker.isActive(convId)) {
                                startProgressTracking("Processing")
                            }
                        } else {
                            // Server says idle - always stop progress if it's running
                            if (progressTracker.isActive(convId)) {
                                DebugLogger.d(TAG, "ChatViewModel: Foreground sync - stopping progress (server confirmed idle)")
                                stopProgressTracking()
                                queueManager.clearSentQueueMessages(_currentConversationIdFlow.value)
                            }
                        }

                        // Update todos
                        if (!stateResponse.todos.isNullOrEmpty()) {
                            val todoItems = stateResponse.todos!!.map { payload ->
                                com.claudecode.native.data.model.TodoItem(
                                    content = payload.content,
                                    status = payload.status,
                                    activeForm = payload.activeForm,
                                    priority = payload.priority,
                                    id = payload.id
                                )
                            }
                            scope.launch {
                                val updated = progressTracker.updateTodos(convId, todoItems)
                                if (updated) {
                                    DebugLogger.d(TAG, "ChatViewModel: Foreground sync - updating todos: ${todoItems.size} items")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        DebugLogger.d(TAG, "ChatViewModel: Failed to sync state on foreground: ${e.message}")
                        // Continue with message sync even if state sync fails
                    }

                    // Skip message sync if streaming is now active
                    if (_isStreaming.value) {
                        DebugLogger.d(TAG, "ChatViewModel: Skipping message sync - streaming is active")
                        return@launch
                    }

                    // Skip message sync for draft sessions or sessions with no messages yet
                    // (new conversations don't need to reload messages)
                    if (_isDraftSession.value || _messages.value.isEmpty()) {
                        DebugLogger.d(TAG, "ChatViewModel: Skipping message sync - draft session or no messages yet")
                        return@launch
                    }

                    // Reload messages from filesystem
                    DebugLogger.d(TAG, "ChatViewModel: Syncing messages on foreground for $encodedPath / $sessionId")
                    loadMessagesFromFilesystem(encodedPath, sessionId, convId, "SYNC")
                } else {
                    // Legacy flow - reload via conversation API
                    DebugLogger.d(TAG, "ChatViewModel: Syncing messages on foreground (legacy) for $convId")
                    loadMessages(convId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogger.d(TAG, "ChatViewModel: Failed to sync on foreground: ${e.message}")
            }
        }
    }

    /**
     * Syncs session state via REST API as a fallback when WebSocket messages are missed.
     * Updates streaming state, progress status, and todos from server.
     * This should be called periodically or when WebSocket connection is unstable.
     *
     * REST API reads the actual session file and checks stop_reason to determine if
     * streaming is truly complete, providing authoritative state information.
     */
    fun syncStateViaRest() {
        val encodedPath = currentEncodedPath ?: return
        val sessionId = currentClaudeSession ?: return
        val convId = currentConversationId ?: return

        scope.launch {
            try {
                DebugLogger.d(TAG, "ChatViewModel: Syncing state via REST for $encodedPath / $sessionId")
                val stateResponse = claudeHistoryApi.getSessionState(encodedPath, sessionId)

                // REST API reads the actual file state and checks stop_reason,
                // so it provides authoritative information about streaming completion
                streamingMutex.withLock {
                    if (_isStreaming.value != stateResponse.isStreaming) {
                        DebugLogger.d(TAG, "ChatViewModel: REST sync - updating streaming state: ${stateResponse.isStreaming} (was: ${_isStreaming.value})")
                        _isStreaming.value = stateResponse.isStreaming
                    }
                }

                // Update progress tracking based on REST state
                if (stateResponse.isStreaming) {
                    // Server says streaming, start progress if not already
                    if (!progressTracker.isActive(convId)) {
                        startProgressTracking("Processing")
                    }
                } else {
                    // Server says not streaming (has stop_reason), stop progress
                    if (progressTracker.isActive(convId)) {
                        DebugLogger.d(TAG, "ChatViewModel: REST sync - stopping progress (server confirmed idle)")
                        stopProgressTracking()
                        queueManager.clearSentQueueMessages(_currentConversationIdFlow.value)
                    }
                }

                // Update todos in progress status (always update, todos are additive/safe)
                if (!stateResponse.todos.isNullOrEmpty()) {
                    val todoItems = stateResponse.todos!!.map { payload ->
                        com.claudecode.native.data.model.TodoItem(
                            content = payload.content,
                            status = payload.status,
                            activeForm = payload.activeForm,
                            priority = payload.priority,
                            id = payload.id
                        )
                    }
                    scope.launch {
                        val updated = progressTracker.updateTodos(convId, todoItems)
                        if (updated) {
                            DebugLogger.d(TAG, "ChatViewModel: REST sync - updating todos: ${todoItems.size} items")
                        }
                    }
                }

                DebugLogger.d(TAG, "ChatViewModel: REST sync complete - state=${stateResponse.sessionState}, streaming=${stateResponse.isStreaming}, todos=${stateResponse.todos?.size ?: 0}")

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogger.d(TAG, "ChatViewModel: Failed to sync state via REST: ${e.message}")
                // Don't propagate error - this is a fallback mechanism
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
                // Unsubscribe from current conversation and disconnect history watch
                unifiedWebSocketClient.unsubscribe()
                historyWatchClient.disconnect()

                currentConversationId = null
                _currentConversationIdFlow.value = null
                currentEncodedPath = null
                currentClaudeSession = null
                currentProjectPath = null

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
                unifiedWebSocketClient.resetAndReconnect()
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
     * Includes any attached images with the message.
     *
     * @param content The message text to send
     */
    fun sendMessage(content: String) {
        DebugLogger.d(TAG, "sendMessage() ENTRY: content='${content.take(50)}...'")
        val images = _attachedImages.value
        DebugLogger.d(TAG, "sendMessage(): attachedImages=${images.size}, isDraft=${_isDraftSession.value}, connectionState=${connectionState.value}, isStreaming=${_isStreaming.value}")

        if (content.isBlank() && images.isEmpty()) {
            DebugLogger.d(TAG, "sendMessage(): EARLY RETURN - content blank and no images")
            return
        }

        // Clear attached images immediately after capturing them
        _attachedImages.value = emptyList()

        // For draft sessions, we need to create the session first
        if (_isDraftSession.value) {
            DebugLogger.d(TAG, "sendMessage(): Draft session - calling createSessionAndSendMessage()")
            scope.launch {
                createSessionAndSendMessage(content, images)
            }
            return
        }

        if (connectionState.value != ConnectionState.Connected) {
            DebugLogger.w(TAG, "sendMessage(): EARLY RETURN - not connected!")
            _error.value = "Not connected"
            return
        }

        // If streaming is in progress, queue the message on the server (with local fallback)
        // Claude Code CLI will handle the queuing on its side
        // Both text and image messages can be queued
        if (_isStreaming.value) {
            DebugLogger.d(TAG, "sendMessage(): Streaming in progress - queueing message")
            if (queueManager.isQueueFull(_currentConversationIdFlow.value)) {
                _error.value = "Message queue is full (${QueueManager.MAX_QUEUED_MESSAGES} messages). Please wait for current response to complete."
                return
            }

            // Add to server queue (falls back to local on error)
            scope.launch {
                val queuedMessage = queueManager.addToServerQueue(_currentConversationIdFlow.value, content, images)
                queueManager.updateCurrentQueue(_currentConversationIdFlow.value) { queue -> queue + queuedMessage }
                DebugLogger.d(TAG, "Queued message (source=${queuedMessage.source}, id=${queuedMessage.id}, images=${images.size}): ${content.take(50)}...")

                // Clear attached images after queuing
                if (images.isNotEmpty()) {
                    clearAttachedImages()
                }

                // Trigger scroll to bottom when message is queued
                _scrollToBottomSignal.update { it + 1 }

                // Send the queued message immediately - Claude Code CLI handles its own queue
                // The message will be processed by Claude when ready
                // Message stays in queue until CLI sends "dequeue" event
                try {
                    DebugLogger.d(TAG, "Sending queued message to CLI (id=${queuedMessage.id})")
                    if (queuedMessage.images.isEmpty()) {
                        unifiedWebSocketClient.sendChat(queuedMessage.content)
                    } else {
                        val imageDtos = queuedMessage.images.map { img ->
                            ImageContentDto(
                                type = "base64",
                                mediaType = img.mediaType,
                                data = Base64.encode(img.data)
                            )
                        }
                        unifiedWebSocketClient.sendChatWithImages(queuedMessage.content, imageDtos)
                    }
                    DebugLogger.d(TAG, "Queued message sent to CLI, waiting for dequeue event (id=${queuedMessage.id})")
                    // Don't remove from queue here - wait for CLI's dequeue event
                } catch (e: Exception) {
                    val isConnectionError = e is kotlinx.coroutines.CancellationException ||
                        (e is IllegalStateException && e.message?.contains("not connected") == true)

                    if (isConnectionError) {
                        // Connection-related error: keep message in queue for retry when reconnected
                        DebugLogger.d(TAG, "Connection error while sending queued message (id=${queuedMessage.id}), will retry on reconnect: ${e.message}")
                    } else {
                        // Other error: keep message in queue for future retry (don't lose the message)
                        // The message will be retried when streaming completes or user reconnects
                        DebugLogger.e(TAG, "Failed to send queued message (id=${queuedMessage.id}): ${e.message}", e)
                        _error.value = "Failed to send queued message: ${e.message}"
                    }
                }
            }
            return
        }

        DebugLogger.d(TAG, "sendMessage(): Not streaming - calling sendMessageInternal()")
        scope.launch {
            sendMessageInternal(content, images)
        }
    }

    /**
     * Creates a new session from draft mode and sends the first message.
     * This is called when user sends the first message in a draft session.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun createSessionAndSendMessage(content: String, images: List<AttachedImage> = emptyList()) {
        val encodedPath = currentEncodedPath ?: run {
            _error.value = "No project path available"
            return
        }

        try {
            // Generate a new session ID
            val newSessionId = kotlin.uuid.Uuid.random().toString()
            DebugLogger.d(TAG, "ChatViewModel: Creating session from draft: $newSessionId for $encodedPath")

            // Update internal state
            currentClaudeSession = newSessionId
            val newConversationId = "$newSessionId?project=$encodedPath"
            currentConversationId = newConversationId
            _currentConversationIdFlow.value = newConversationId

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
            DebugLogger.d(TAG, "ChatViewModel: Connecting WebSocket for new session: $newConversationId")
            connectUnified(token, newConversationId, newSessionId, encodedPath)

            // Connect history watch for real-time updates
            DebugLogger.d(TAG, "ChatViewModel: Connecting history watch for new session: $encodedPath / $newSessionId")
            historyWatchClient.connect(encodedPath, newSessionId, token)

            // Now send the message with images
            sendMessageInternal(content, images)

            // Defer session created event until first server response (STREAM message)
            // HistoryWatch serves as fallback for edge cases where STREAM may be missed
            DebugLogger.d(TAG, "ChatViewModel: Session created, deferring event until first response: $newSessionId")
            sessionCreatedMutex.withLock {
                pendingSessionCreatedEmit = SessionCreatedInfo(newSessionId, encodedPath)
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.d(TAG, "ChatViewModel: Failed to create session from draft: ${e.message}")
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
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun sendMessageInternal(content: String, images: List<AttachedImage> = emptyList()) {
        DebugLogger.d(TAG, "sendMessageInternal() ENTRY: content='${content.take(50)}...', imageCount=${images.size}")
        try {
            // Build content blocks (text + images)
            val blocks = mutableListOf<ContentBlock>()
            if (content.isNotBlank()) {
                blocks.add(ContentBlock.Text(content))
            }
            // Add image blocks for display
            images.forEach { img ->
                val base64Data = Base64.encode(img.data)
                blocks.add(ContentBlock.Image(ImageSource.Base64(base64Data, img.mediaType)))
            }

            // Add user message to the list (marked as pending until confirmed)
            val messageId = generateMessageId()
            val userMessage = ChatMessage(
                id = messageId,
                role = MessageRole.USER,
                blocks = blocks,
                isStreaming = false,
                isPending = true  // Mark as pending until confirmed by history watch
            )

            // Track this pending user message to prevent duplicate from history watch
            // Use normalized content hash to handle whitespace differences in serialization
            // Lock ordering: mutex (#2) → mapsMutex (#3) to prevent deadlocks
            val normalizedHash = normalizeForComparison(content).hashCode()
            DebugLogger.d(TAG, "Added pending user message (hash=$normalizedHash, images=${images.size}): ${content.take(50)}...")

            mutex.withLock {
                mapsMutex.withLock {
                    pendingUserMessages[normalizedHash] = messageId
                }
                _messages.value = _messages.value + userMessage
                DebugLogger.d(TAG, "Added user message id=${userMessage.id}, total=${_messages.value.size}")
            }

            // Trigger scroll to bottom for the new user message (atomic update)
            _scrollToBottomSignal.update { it + 1 }

            // Send via WebSocket (with images if present)
            DebugLogger.d(TAG, "About to send message via WebSocket, content='${content.take(50)}...', imageCount=${images.size}")
            try {
                if (images.isEmpty()) {
                    DebugLogger.d(TAG, "Calling unifiedWebSocketClient.sendChat()")
                    unifiedWebSocketClient.sendChat(content)
                    DebugLogger.d(TAG, "sendChat() completed successfully")
                } else {
                    DebugLogger.d(TAG, "Preparing imageDtos for ${images.size} images")
                    val imageDtos = images.map { img ->
                        DebugLogger.d(TAG, "  Converting image: mediaType=${img.mediaType}, dataSize=${img.data.size}")
                        ImageContentDto(
                            type = "base64",
                            mediaType = img.mediaType,
                            data = Base64.encode(img.data)
                        )
                    }
                    DebugLogger.d(TAG, "Calling unifiedWebSocketClient.sendChatWithImages()")
                    unifiedWebSocketClient.sendChatWithImages(content, imageDtos)
                    DebugLogger.d(TAG, "sendChatWithImages() completed successfully")
                }
            } catch (e: Exception) {
                DebugLogger.e(TAG, "ERROR sending message: ${e.message}", e)
                throw e
            }

            // Prepare for streaming response (use streamingMutex for consistency with other handlers)
            streamingMutex.withLock {
                _isStreaming.value = true
            }

            // Start progress tracking for status line
            startProgressTracking("Processing")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.toUserMessage()
            streamingMutex.withLock {
                _isStreaming.value = false
            }
            stopProgressTracking()
            queueManager.clearSentQueueMessages(_currentConversationIdFlow.value)
        }
    }

    /**
     * Logs queue status when streaming completes.
     * Messages are now sent immediately when queued, so this function only
     * logs the queue state for debugging. Queue is cleared via dequeue events from CLI.
     */
    private suspend fun logQueueStatusOnStreamingComplete() {
        val queue = queueManager.getCurrentQueue(_currentConversationIdFlow.value)
        if (queue.isNotEmpty()) {
            DebugLogger.d(TAG, "ChatViewModel: Streaming complete, ${queue.size} messages in queue (already sent to CLI)")
        }
    }

    /**
     * Cancels a specific queued message by its ID.
     * Only LOCAL messages can be cancelled from this app.
     * Uses atomic update to prevent race conditions.
     *
     * @param messageId The ID of the queued message to cancel
     */
    /**
     * Cancels a specific queued message by its ID.
     * Delegates to QueueManager.
     */
    fun cancelQueuedMessage(messageId: String) {
        queueManager.cancelQueuedMessage(_currentConversationIdFlow.value, messageId)
    }

    /**
     * Clears all queued messages for the current conversation.
     * Delegates to QueueManager.
     */
    fun clearQueuedMessages() {
        queueManager.clearQueuedMessages(_currentConversationIdFlow.value)
    }

    /**
     * Stops the current generation/streaming response.
     */
    fun stopGeneration() {
        scope.launch {
            try {
                unifiedWebSocketClient.sendStop()
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
                DebugLogger.d(TAG, "ChatViewModel: Loaded ${response.count} commands (${response.builtIn.size} builtin, ${response.custom.size} custom)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogger.d(TAG, "ChatViewModel: Failed to load commands: ${e.message}")
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
                            DebugLogger.d(TAG, "ChatViewModel:1231 - Added builtin result id=${resultMessage.id}, total=${_messages.value.size}")
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
                val wasNotStreaming = streamingMutex.withLock {
                    // If we receive stream chunks, ensure streaming state is active
                    // This handles reconnection scenarios where ViewModel was recreated
                    if (!_isStreaming.value) {
                        _isStreaming.value = true
                        true
                    } else {
                        false
                    }
                }
                // Start progress tracking if streaming just started (from WebSocket reconnection)
                if (wasNotStreaming) {
                    startProgressTracking("Processing")
                }

                // Emit session created event on first stream response
                // This ensures sidebar refresh happens when server actually starts responding,
                // not waiting for HistoryWatch filesystem detection which can be delayed
                sessionCreatedMutex.withLock {
                    pendingSessionCreatedEmit?.let { sessionInfo ->
                        DebugLogger.d(TAG, "ChatViewModel: First STREAM message received, emitting session created event: ${sessionInfo.sessionId}")
                        _sessionCreatedEvent.value = sessionInfo
                        pendingSessionCreatedEmit = null
                    }
                }

                // Stream content is now handled via HistoryWatch messages
            }

            MessageType.COMPLETE -> {
                // Finalize the streaming message
                DebugLogger.d(TAG, "ChatViewModel: Received COMPLETE message, calling finalizeStreamingMessage()")
                finalizeStreamingMessage()
            }

            MessageType.ERROR -> {
                // Handle error from server
                streamingMutex.withLock {
                    _error.value = message.error ?: "Unknown error"
                    _isStreaming.value = false
                }
                // Remove the first queued message if any - it's likely the one that failed
                // (FIFO queue: first message is the one being processed)
                val queue = queueManager.getCurrentQueue(_currentConversationIdFlow.value)
                if (queue.isNotEmpty()) {
                    DebugLogger.d(TAG, "ChatViewModel: Removing first queued message due to error")
                    queueManager.updateCurrentQueue(_currentConversationIdFlow.value) { q -> q.drop(1) }
                }
            }

            MessageType.STATUS -> {
                // Status updates can be logged or used for UI indicators
                // Currently just acknowledging them
            }

            MessageType.PONG -> {
                // Pong response to keep-alive ping
            }

            MessageType.QUEUE_ADD, MessageType.QUEUE_REMOVE, MessageType.QUEUE_SYNC -> {
                // Handle queue updates from server
                queueManager.handleQueueWebSocketMessage(message, _currentConversationIdFlow.value)
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
                DebugLogger.d(TAG, "ChatViewModel: History watch connected for ${event.sessionId}")
            }

            is HistoryWatchEvent.NewMessages -> {
                // Validate event belongs to current conversation to prevent stale messages
                // from previous conversation appearing after switching chat rooms
                if (event.sessionId != currentClaudeSession || event.encodedPath != currentEncodedPath) {
                    DebugLogger.d(TAG, "ChatViewModel: Ignoring history watch event for stale session " +
                            "(event: ${event.sessionId}, current: $currentClaudeSession)")
                    return
                }

                DebugLogger.d(TAG, "ChatViewModel: Received ${event.messages.size} new messages from history watch")

                // Fallback: Emit session created event if not already emitted via STREAM message
                // This handles edge cases where STREAM messages might be missed but HistoryWatch detects the session
                sessionCreatedMutex.withLock {
                    pendingSessionCreatedEmit?.let { sessionInfo ->
                        if (sessionInfo.sessionId == event.sessionId) {
                            DebugLogger.d(TAG, "ChatViewModel: HistoryWatch fallback - emitting session created event: ${sessionInfo.sessionId}")
                            _sessionCreatedEvent.value = sessionInfo
                            pendingSessionCreatedEmit = null
                        }
                    }
                }

                // Handle queue-operation events first (terminal queued messages)
                // Use atomic updates to prevent race conditions with concurrent queue operations
                for (claudeMsg in event.messages) {
                    if (claudeMsg.type == "queue-operation") {
                        val operation = claudeMsg.operation
                        val queueContent = claudeMsg.content ?: continue

                        when (operation) {
                            "enqueue" -> {
                                // Skip system notifications (bash-notification, etc.) - not user messages
                                if (queueContent.trimStart().startsWith("<bash-notification>")) {
                                    DebugLogger.d(TAG, "ChatViewModel: Skipping bash-notification enqueue (not a user message)")
                                    continue
                                }

                                // Add to queued messages (from Claude CLI's perspective)
                                // Use UUID-style ID to avoid timestamp collision
                                val cliQueuedMessage = QueuedMessage(
                                    id = "cli_${generateMessageId()}",
                                    content = queueContent,
                                    queuedAt = Clock.System.now().toEpochMilliseconds(),
                                    source = QueuedMessageSource.CLI
                                )
                                queueManager.updateCurrentQueue(_currentConversationIdFlow.value) { queue -> queue + cliQueuedMessage }
                                DebugLogger.d(TAG, "ChatViewModel: Queued message from CLI (id=${cliQueuedMessage.id}): ${queueContent.take(50)}...")
                            }
                            "dequeue", "clear" -> {
                                // Remove first message from queue (FIFO)
                                val queue = queueManager.getCurrentQueue(_currentConversationIdFlow.value)
                                if (queue.isNotEmpty()) {
                                    DebugLogger.d(TAG, "ChatViewModel: Dequeued message from CLI (operation=$operation)")
                                    queueManager.updateCurrentQueue(_currentConversationIdFlow.value) { q -> q.drop(1) }
                                }
                            }
                            "remove" -> {
                                // Remove all messages queued before the remove event timestamp
                                // This ensures proper cleanup when CLI processes queued messages
                                val removeTimestamp = claudeMsg.timestamp?.toEpochMilliseconds()
                                    ?: Clock.System.now().toEpochMilliseconds()

                                val queue = queueManager.getCurrentQueue(_currentConversationIdFlow.value)
                                val messagesToRemove = queue.filter { it.queuedAt <= removeTimestamp }

                                if (messagesToRemove.isNotEmpty()) {
                                    DebugLogger.d(TAG, "ChatViewModel: Remove operation - removing ${messagesToRemove.size} queued message(s) before timestamp $removeTimestamp")
                                    queueManager.updateCurrentQueue(_currentConversationIdFlow.value) { q -> q.filter { it.queuedAt > removeTimestamp } }
                                }

                                // Add removed messages as pending user messages
                                messagesToRemove.forEach { msg ->
                                    val normalizedContent = normalizeForComparison(msg.content)
                                    val normalizedHash = normalizedContent.hashCode()

                                    mutex.withLock {
                                        // Check if already confirmed by HistoryWatch
                                        val alreadyConfirmed = _messages.value.any { existingMsg ->
                                            existingMsg.role == MessageRole.USER &&
                                            !existingMsg.isPending &&
                                            normalizeForComparison(existingMsg.content) == normalizedContent
                                        }

                                        if (alreadyConfirmed) {
                                            DebugLogger.d(TAG, "ChatViewModel: Removed message already confirmed, skipping")
                                        } else {
                                            val messageId = "pending_${generateMessageId()}"
                                            val blocks = mutableListOf<ContentBlock>()
                                            if (msg.content.isNotBlank()) {
                                                blocks.add(ContentBlock.Text(msg.content))
                                            }
                                            msg.images.forEach { img ->
                                                blocks.add(ContentBlock.Image(
                                                    ImageSource.Base64(
                                                        data = kotlin.io.encoding.Base64.encode(img.data),
                                                        mediaType = img.mediaType
                                                    )
                                                ))
                                            }

                                            val userMessage = ChatMessage(
                                                id = messageId,
                                                role = MessageRole.USER,
                                                blocks = blocks,
                                                isStreaming = false,
                                                isPending = true
                                            )

                                            mapsMutex.withLock {
                                                pendingUserMessages[normalizedHash] = messageId
                                            }
                                            _messages.value = _messages.value + userMessage
                                            DebugLogger.d(TAG, "ChatViewModel: Added removed message as pending (id=$messageId)")
                                        }
                                    }

                                    _scrollToBottomSignal.update { it + 1 }
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
                    DebugLogger.d(TAG, "ChatViewModel: [$idx] role=${msg.role}, preview='$textPreview...'")
                }

                // Check if any message is from assistant - this means Claude is processing
                val assistantMessage = event.messages.find { it.message?.role == "assistant" }
                if (assistantMessage != null) {
                    // Synchronize streaming state changes to prevent race conditions with WebSocket
                    val shouldStartProgress = streamingMutex.withLock {
                        val wasNotStreaming = !_isStreaming.value
                        if (wasNotStreaming) {
                            DebugLogger.d(TAG, "ChatViewModel: Detected assistant activity from HistoryWatch, activating streaming state")
                            _isStreaming.value = true
                            isStreamingFromHistoryWatch = true
                        }
                        wasNotStreaming
                    }
                    // Start progress tracking if streaming just started (from HistoryWatch)
                    if (shouldStartProgress) {
                        startProgressTracking("Processing")
                    }
                }

                // Update todos in progress status if available and changed
                if (event.todos.isNotEmpty()) {
                    currentConversationId?.let { convId ->
                        val todoItems = event.todos.map { payload ->
                            com.claudecode.native.data.model.TodoItem(
                                content = payload.content,
                                status = payload.status,
                                activeForm = payload.activeForm,
                                priority = payload.priority,
                                id = payload.id
                            )
                        }
                        scope.launch {
                            val updated = progressTracker.updateTodos(convId, todoItems)
                            if (updated) {
                                DebugLogger.d(TAG, "ChatViewModel: Updated todos (${event.todos.size} items, ${event.todos.count { it.isCompleted }} completed)")
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

                DebugLogger.d(TAG, "ChatViewModel: Found ${newToolUses.size} tool_uses, ${newToolResults.size} tool_results")

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

                // Convert ClaudeMessages to ChatMessages with matched tools
                // Take a snapshot of sessionToolUses for use in the map operation
                val toolUsesSnapshot = mapsMutex.withLock { sessionToolUses.toMap() }

                val newChatMessages = event.messages.mapNotNull { claudeMsg ->
                    val msg = claudeMsg.message ?: return@mapNotNull null
                    val role = msg.role
                    val blocks = parseMessageContent(msg.content, claudeMsg.uuid)

                    // Update tool blocks with results from session maps
                    val blocksWithResults = updateBlocksWithToolResults(blocks, toolUsesSnapshot)

                    // Skip tool_result-only messages
                    if (hasOnlyToolResults(msg.content)) {
                        return@mapNotNull null
                    }

                    // Skip meta messages (skill content injected by Claude Code, not user-typed)
                    if (claudeMsg.isMeta) {
                        return@mapNotNull null
                    }

                    // Skip messages with no content blocks
                    if (blocksWithResults.isEmpty()) return@mapNotNull null

                    // Get text content for validation
                    val textContent = blocksWithResults.filterIsInstance<ContentBlock.Text>()
                        .joinToString("\n\n") { it.content }

                    // Skip compaction/summary messages (system-generated, not user content)
                    if (isCompactionMessage(textContent)) return@mapNotNull null

                    // Use UUID from Claude message, fallback to timestamp-based ID
                    // Use same fallback pattern as initial load for consistency
                    val messageId = claudeMsg.uuid
                        ?: "msg_${claudeMsg.timestamp?.toEpochMilliseconds() ?: Clock.System.now().toEpochMilliseconds()}"

                    ChatMessage(
                        id = messageId,
                        role = when (role) {
                            "user" -> MessageRole.USER
                            "assistant" -> MessageRole.ASSISTANT
                            else -> return@mapNotNull null
                        },
                        blocks = blocksWithResults,
                        isStreaming = false,
                        gitBranch = claudeMsg.gitBranch,
                        agentId = claudeMsg.agentId,
                        isSidechain = claudeMsg.isSidechain
                    )
                }
                    // Deduplicate by UUID - keep last message for each UUID
                    .groupBy { it.id }
                    .map { (_, messages) -> messages.last() }

                // Update existing messages that have tools without results
                val toolUsesSnap = mapsMutex.withLock { sessionToolUses.toMap() }
                mutex.withLock {
                    val currentMessages = _messages.value.toMutableList()
                    var messagesUpdated = false

                    for (i in currentMessages.indices) {
                        val msg = currentMessages[i]
                        val hasToolsWithoutResults = msg.blocks.any { block ->
                            block is ContentBlock.Tool && block.info.result == null
                        }
                        if (hasToolsWithoutResults) {
                            val updatedBlocks = updateBlocksWithToolResults(msg.blocks, toolUsesSnap)
                            if (updatedBlocks != msg.blocks) {
                                currentMessages[i] = msg.copy(blocks = updatedBlocks)
                                messagesUpdated = true
                                DebugLogger.d(TAG, "ChatViewModel: Updated message ${msg.id} with tool results")
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
                        // Build ID -> index map for O(1) lookup
                        val idToIndex = currentMessages.withIndex()
                            .associate { (index, msg) -> msg.id to index }
                            .toMutableMap()
                        var updated = false

                        for (newMsg in newChatMessages) {
                            // Check if this is a pending user message we already added locally
                            val normalizedContentHash = normalizeForComparison(newMsg.content).hashCode()
                            val pendingMsgId = if (newMsg.role == MessageRole.USER) {
                                mapsMutex.withLock { pendingUserMessages.remove(normalizedContentHash) }
                            } else null

                            // Also remove matching queued messages when user message is confirmed
                            if (newMsg.role == MessageRole.USER) {
                                val normalizedContent = normalizeForComparison(newMsg.content)
                                queueManager.updateCurrentQueue(_currentConversationIdFlow.value) { queue ->
                                    val matchingIndex = queue.indexOfFirst {
                                        normalizeForComparison(it.content) == normalizedContent
                                    }
                                    if (matchingIndex >= 0) {
                                        DebugLogger.d(TAG, "ChatViewModel: Removed confirmed queued message from queue")
                                        queue.filterIndexed { index, _ -> index != matchingIndex }
                                    } else {
                                        queue
                                    }
                                }
                            }

                            if (pendingMsgId != null) {
                                // This user message was confirmed - update isPending to false (O(1) lookup)
                                val pendingIndex = idToIndex[pendingMsgId]
                                if (pendingIndex != null) {
                                    currentMessages[pendingIndex] = currentMessages[pendingIndex].copy(isPending = false)
                                    updated = true
                                }
                                continue
                            }

                            // O(1) ID lookup using index map
                            val existingIndex = idToIndex[newMsg.id]

                            if (existingIndex != null) {
                                // Message exists - update with latest blocks
                                val existing = currentMessages[existingIndex]
                                currentMessages[existingIndex] = existing.copy(
                                    blocks = newMsg.blocks,
                                    isStreaming = false
                                )
                                updated = true
                            } else {
                                // New message - add it and update index
                                idToIndex[newMsg.id] = currentMessages.size
                                currentMessages.add(newMsg)
                                updated = true
                            }
                        }

                        if (updated) {
                            _messages.value = currentMessages.toList()
                            DebugLogger.d(TAG, "ChatViewModel:1742 - HistoryWatch update, total=${_messages.value.size}")
                        }
                    }
                }

                // Check session state from HistoryWatch to detect streaming completion
                // This is a fallback when WebSocket COMPLETE message is missed
                if (event.sessionState == SessionState.IDLE) {
                    streamingMutex.withLock {
                        if (_isStreaming.value) {
                            DebugLogger.d(TAG, "ChatViewModel: HistoryWatch detected IDLE state, finalizing streaming")
                            _isStreaming.value = false
                            isStreamingFromHistoryWatch = false
                        }
                    }
                    // Stop progress tracking when session becomes idle
                    stopProgressTracking()
                    // Clear queued messages that were sent to CLI (fallback for missed dequeue events)
                    queueManager.clearSentQueueMessages(_currentConversationIdFlow.value)
                }
            }

            is HistoryWatchEvent.Error -> {
                DebugLogger.d(TAG, "ChatViewModel: History watch error: ${event.message}")
                // Clear pending event to prevent stale state
                sessionCreatedMutex.withLock {
                    pendingSessionCreatedEmit = null
                }
            }

            is HistoryWatchEvent.Disconnected -> {
                DebugLogger.d(TAG, "ChatViewModel: History watch disconnected")
                // Clear pending event to prevent stale state
                sessionCreatedMutex.withLock {
                    pendingSessionCreatedEmit = null
                }
            }
        }
    }

    private suspend fun finalizeStreamingMessage() {
        // Reset streaming state - message saving is handled by HistoryWatch
        streamingMutex.withLock {
            _isStreaming.value = false
            isStreamingFromHistoryWatch = false
        }

        // Stop progress tracking for status line
        stopProgressTracking()

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
                val idToIndex = currentMessages.withIndex()
                    .associate { (i, m) -> m.id to i }
                var updated = false
                for ((_, pendingMsgId) in pendingEntries) {
                    val index = idToIndex[pendingMsgId] ?: continue
                    if (currentMessages[index].isPending) {
                        currentMessages[index] = currentMessages[index].copy(isPending = false)
                        updated = true
                    }
                }
                if (updated) {
                    _messages.value = currentMessages
                }
            }
        }

        // Trigger scroll to bottom signal for UI (atomic update)
        _scrollToBottomSignal.update { it + 1 }

        // Clear queued messages that were sent to CLI (LOCAL/SERVER sources)
        // CLI should have processed them by now; if no dequeue event came, it's because
        // CLI finished processing all queued messages. This is a fallback to prevent
        // stuck queue state when dequeue events are missed.
        queueManager.clearSentQueueMessages(_currentConversationIdFlow.value)
    }


    @OptIn(ExperimentalUuidApi::class)
    private fun generateMessageId(): String {
        return kotlin.uuid.Uuid.random().toString()
    }

    // ============================================================================
    // Progress Status Line Methods
    // ============================================================================

    /**
     * Starts progress tracking for the status line UI.
     * Called when streaming begins - starts elapsed time counter and shows the status line.
     * Tracks progress per-conversation to support multiple concurrent sessions.
     *
     * @param statusText Initial status text to display (e.g., "Thinking", "Processing")
     */
    private fun startProgressTracking(statusText: String = "") {
        val convId = currentConversationId ?: return
        scope.launch {
            progressTracker.startProgressTracking(convId, statusText, scope)
        }
    }

    /**
     * Stops progress tracking and hides the status line.
     * Called when streaming ends (complete, error, or stop).
     * Stops tracking for the current conversation only.
     */
    private fun stopProgressTracking() {
        val convId = currentConversationId ?: return
        scope.launch {
            progressTracker.stopProgressTracking(convId)
        }
    }

    /**
     * Clears all progress-related state for a specific conversation.
     * Used when switching conversations or initializing draft sessions to prevent stale state.
     * Unlike stopProgressTracking(), this completely removes the progress entry.
     */
    private fun clearProgressStateForConversation(convId: String) {
        scope.launch {
            progressTracker.clearProgressState(convId)
        }
    }

}
