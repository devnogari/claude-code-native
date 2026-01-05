package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.api.QueueApi
import com.claudecode.native.data.model.OperationMode
import com.claudecode.native.data.repository.PreferenceKeys
import com.claudecode.native.data.repository.PreferencesRepository
import com.claudecode.native.data.model.OperationMode.Companion.next
import com.claudecode.native.data.model.QueuedMessageDto
import com.claudecode.native.data.model.Command
import com.claudecode.native.data.model.ExecuteCommandResponse
import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.data.websocket.ConnectionState
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
 * @param messageStore Centralized message state management
 * @param sessionStateManager Session identity and state tracking
 * @param scope Injected coroutine scope for lifecycle management
 */
class ChatViewModel(
    private val unifiedWebSocketClient: UnifiedWebSocketClient,
    private val apiClient: ApiClient,
    private val conversationApi: ConversationApi,
    private val projectApi: ProjectApi,
    private val claudeHistoryApi: ClaudeHistoryApi,
    private val commandExecutor: CommandExecutor,
    private val queueApi: QueueApi,
    private val toolTracker: ToolTracker,
    private val messageStore: MessageStore,
    private val sessionStateManager: SessionStateManager,
    private val messageLoader: MessageLoader,
    private val connectionManager: ConnectionManager,
    private val preferencesRepository: PreferencesRepository,
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
        onError = { error -> messageStore.setError(error) },
        isFilesystemSession = { SessionStateManager.isFilesystemSessionId(it) },
        generateMessageId = { messageStore.generateMessageId() },
        isStreamingActive = { messageStore.getStreamingValue() }
    )

    /** Progress tracker for per-conversation streaming progress */
    private val progressTracker = ProgressTracker()

    /** History watch handler for processing real-time file change events */
    private val historyWatchHandler = HistoryWatchHandler(
        messageStore = messageStore,
        sessionStateManager = sessionStateManager,
        toolTracker = toolTracker,
        queueManager = queueManager,
        progressTracker = progressTracker,
        scope = scope
    )

    // ============================================================================
    // State delegated to MessageStore
    // ============================================================================

    /**
     * Thread Safety Notes:
     * - Message state and synchronization is handled internally by MessageStore.
     * - Tool tracking is delegated to ToolTracker which manages its own mutex internally.
     * - SessionStateManager handles session identity and sessionCreatedMutex internally.
     * - ChatViewModel should use MessageStore's public API methods (which handle locking)
     *   rather than accessing mutexes directly.
     */

    // Internal access to message list for read operations (returns current value)
    private val currentMessages get() = messageStore.getMessagesValue()
    // Internal access to streaming state for read operations
    private val isStreamingValue get() = messageStore.getStreamingValue()

    /** Flow of chat messages in the conversation. */
    val messages: StateFlow<List<ChatMessage>> = messageStore.messages

    /** True when the assistant is actively streaming a response. */
    val isStreaming: StateFlow<Boolean> = messageStore.isStreaming

    // Session state delegated to SessionStateManager
    // Convenience accessor for internal use
    private val _currentConversationIdFlow get() = sessionStateManager.currentConversationIdFlow

    /** Current conversation's queued messages (exposes only active conversation's queue). */
    val queuedMessages: StateFlow<List<QueuedMessage>> by lazy {
        combine(queueManager.queuedMessagesMap, sessionStateManager.currentConversationIdFlow) { map, convId ->
            convId?.let { map[it] } ?: emptyList()
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    /** Current error message, if any. Delegated to MessageStore. */
    val error: StateFlow<String?> = messageStore.error

    /** Current conversation title for display in UI. Delegated to SessionStateManager. */
    val conversationTitle: StateFlow<String?> = sessionStateManager.conversationTitle

    /** True when this is a draft session (not yet created on server). Delegated to SessionStateManager. */
    val isDraftSession: StateFlow<Boolean> = sessionStateManager.isDraftSession

    /** True when there are more messages to load (pagination). Delegated to MessageStore. */
    val hasMoreMessages: StateFlow<Boolean> = messageStore.hasMoreMessages

    /** True when loading more messages (pagination in progress). Delegated to MessageStore. */
    val isLoadingMore: StateFlow<Boolean> = messageStore.isLoadingMore

    /** Current offset for pagination (number of messages already loaded). Delegated to MessageStore. */
    private val currentMessagesOffset: Int get() = messageStore.currentMessagesOffset

    /**
     * Type alias for SessionCreatedInfo from SessionStateManager.
     */
    @Suppress("unused")
    private typealias SessionCreatedInfo = SessionStateManager.SessionCreatedInfo

    /**
     * Emits session info when a draft session is converted to a real session.
     * UI can observe this to update the sidebar/project list with polling.
     * Delegated to SessionStateManager.
     */
    val sessionCreatedEvent: StateFlow<SessionStateManager.SessionCreatedInfo?>
        get() = sessionStateManager.sessionCreatedEvent

    // sessionCreatedMutex is delegated to SessionStateManager
    private val sessionCreatedMutex get() = sessionStateManager.sessionCreatedMutex


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

    /** Available slash commands (builtin + custom), delegated to CommandExecutor. */
    val availableCommands: StateFlow<List<Command>>
        get() = commandExecutor.availableCommands

    /** True when commands are being loaded from the server, delegated to CommandExecutor. */
    val commandsLoading: StateFlow<Boolean>
        get() = commandExecutor.commandsLoading

    /**
     * Signal for UI to scroll to bottom. Incremented when:
     * - Streaming completes (finalizeStreamingMessage)
     * - User sends a message
     * UI should observe and scroll when value changes.
     * Delegated to MessageStore.
     */
    val scrollToBottomSignal: StateFlow<Int> = messageStore.scrollToBottomSignal

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

    /** Connection state exposed from the connection manager. */
    val connectionState: StateFlow<ConnectionState>
        get() = connectionManager.connectionState

    /** Operation mode exposed from the unified WebSocket client. */
    val operationMode: StateFlow<OperationMode>
        get() = unifiedWebSocketClient.operationMode

    // Session identity - delegated to SessionStateManager
    private val currentConversationId get() = sessionStateManager.currentConversationId
    private val currentEncodedPath get() = sessionStateManager.currentEncodedPath
    private val currentClaudeSession get() = sessionStateManager.currentClaudeSession
    private val currentProjectPath get() = sessionStateManager.currentProjectPath
    private val currentConnectJob get() = sessionStateManager.currentConnectJob
    private val isStreamingFromHistoryWatch get() = sessionStateManager.isStreamingFromHistoryWatch

    // Session-level tool tracking is now delegated to ToolTracker
    // ToolTracker handles thread safety internally via mutex

    companion object {
        private const val TAG = "ChatViewModel"
        /** Marker for draft sessions that haven't been created yet. Delegated to SessionStateManager. */
        const val DRAFT_SESSION_MARKER = SessionStateManager.DRAFT_SESSION_MARKER

        /**
         * Normalizes content for hash comparison.
         * Delegates to MessageParser for consistent normalization across the codebase.
         *
         * - Strips @ file mentions from the beginning (added by Claude CLI for images)
         * - Trims whitespace and normalizes internal whitespace to single spaces
         * This ensures hash comparison works even when content is modified
         * during serialization/deserialization (e.g., trailing spaces removed).
         */
        fun normalizeForComparison(content: String): String {
            return MessageParser.normalizeForComparison(content)
        }

        /**
         * Checks if the given conversation ID represents a filesystem-based session.
         * Delegated to SessionStateManager.
         */
        fun isFilesystemSessionId(conversationId: String): Boolean {
            return SessionStateManager.isFilesystemSessionId(conversationId)
        }
    }

    /**
     * Clears all session state for switching conversations or starting fresh.
     */
    private suspend fun clearSessionState() {
        // Clear tool tracking via ToolTracker
        toolTracker.clearState()
        // Clear message state via MessageStore (includes messages, pagination, pending messages)
        messageStore.clearState()
    }

    /**
     * Loads more (older) messages for pagination.
     * Called when user scrolls to the top of the message list.
     */
    fun loadMoreMessages() {
        messageLoader.loadMoreMessages()
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

        // Collect history watch events for real-time file changes (via unified WebSocket)
        scope.launch {
            DebugLogger.d(TAG, "Starting unifiedWebSocketClient.historyWatchEvents collector")
            unifiedWebSocketClient.historyWatchEvents.collect { event ->
                DebugLogger.d(TAG, "Received historyWatchEvent: ${event::class.simpleName}")
                historyWatchHandler.handleHistoryWatchEvent(event)
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

        // Wire up CommandExecutor callbacks
        commandExecutor.onAddResultMessage = { content ->
            scope.launch {
                val resultMessage = ChatMessage(
                    id = messageStore.generateMessageId(),
                    role = MessageRole.ASSISTANT,
                    blocks = listOf(ContentBlock.Text(content)),
                    isStreaming = false
                )
                messageStore.addMessage(resultMessage)
                DebugLogger.d(TAG, "Added builtin command result, total=${messageStore.getMessageCount()}")
            }
        }
        commandExecutor.onSendMessage = { content ->
            sendMessage(content)
        }
        commandExecutor.onClearMessages = {
            scope.launch {
                messageStore.setMessages(emptyList())
            }
        }
        commandExecutor.onError = { error ->
            messageStore.setError(error)
        }

        // Wire up MessageLoader callbacks
        messageLoader.onLoadCommands = { projectPath ->
            loadCommands(projectPath)
        }
        messageLoader.onClearSessionState = {
            clearSessionState()
        }

        // Wire up HistoryWatchHandler callbacks
        historyWatchHandler.onParseMessageContent = { content, uuid ->
            parseMessageContent(content, uuid)
        }
        historyWatchHandler.onHasOnlyToolResults = { content ->
            hasOnlyToolResults(content)
        }
        historyWatchHandler.onIsCompactionMessage = { text ->
            isCompactionMessage(text)
        }
        historyWatchHandler.getCurrentConversationIdFlow = {
            _currentConversationIdFlow
        }
        historyWatchHandler.onStartProgressTracking = { statusText ->
            startProgressTracking(statusText)
        }
        historyWatchHandler.onStopProgressTracking = {
            stopProgressTracking()
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
            messageStore.setStreaming(state.isStreaming)

            // Update todos from session state
            state.todos?.takeIf { it.isNotEmpty() }?.let { todos ->
                updateProgressFromTodos(todos)
            }

            // Update queue from session state (sync with server)
            state.queue?.takeIf { it.isNotEmpty() }?.let { queue ->
                val convId = _currentConversationIdFlow.value ?: return@launch
                val serverQueue = queue.map { msg ->
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
        if (sessionStateManager.isAlreadyConnected(conversationId)) {
            DebugLogger.d(TAG, "[$connectCallId] SKIP: Already connected to $shortConvId")
            return
        }

        DebugLogger.d(TAG, "[$connectCallId] >>> connect() CALLED with: $shortConvId")
        DebugLogger.d(TAG, "[$connectCallId] Current state: currentConversationId=${currentConversationId?.take(20)}, title=${conversationTitle.value?.take(30)}")

        // Cancel any previous connect job to prevent race conditions when switching rooms quickly
        val prevJob = currentConnectJob
        if (prevJob != null) {
            DebugLogger.d(TAG, "[$connectCallId] Cancelling previous connect job")
            prevJob.cancel()
        }

        sessionStateManager.currentConnectJob = scope.launch {
            try {
                DebugLogger.d(TAG, "[$connectCallId] Inside coroutine, checking if need to disconnect")
                // Disconnect from previous conversation if any
                if (currentConversationId != null && currentConversationId != conversationId) {
                    // Unsubscribe from current conversation (unified connection stays open)
                    unifiedWebSocketClient.unsubscribe()
                    unifiedWebSocketClient.historyUnsubscribe()
                    sessionStateManager.isStreamingFromHistoryWatch = false

                    // NOTE: We intentionally DO NOT clear messages/title here anymore.
                    // Old messages remain visible during loading to prevent empty screen flash.
                    // State will be replaced atomically when new data loads successfully.
                    // If guard check fails (user switched rooms), we abort without clearing.

                    // Only clear transient streaming state (queue is preserved per conversation)
                    messageStore.setStreaming(false)
                    messageStore.clearError()
                    sessionStateManager.clearTransientState()

                    // Clear progress status for previous conversation to prevent stale Processing state
                    // This is important for draft sessions which share the same conversationId pattern
                    currentConversationId?.let { clearProgressStateForConversation(it) }
                }

                // Set conversation ID immediately so subsequent connect() calls know to disconnect
                sessionStateManager.setSessionIdentity(conversationId)
                DebugLogger.d(TAG, "[$connectCallId] Set currentConversationId = $shortConvId")

                val token = apiClient.getAuthToken() ?: run {
                    messageStore.setError("Not authenticated")
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
                        sessionStateManager.setDraftSession(true)
                        sessionStateManager.updateSessionInfo(encodedPath, null)  // No session ID yet
                        sessionStateManager.setConversationTitle("New Chat")

                        // FIX: Clear previous session's messages for draft sessions
                        clearSessionState()
                        DebugLogger.d(TAG, "ChatViewModel: Cleared previous session state for draft")

                        // FIX: Clear progress status for this conversation ID to ensure fresh state
                        // Draft sessions reuse the same conversationId pattern, so old state must be cleared
                        clearProgressStateForConversation(conversationId)

                        // Fetch project info for project path and commands
                        try {
                            val project = claudeHistoryApi.getProject(encodedPath)
                            sessionStateManager.setProjectPath(project.path)
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
                    sessionStateManager.updateSessionInfo(encodedPath, sessionId)

                    DebugLogger.d(TAG, "[$connectCallId] BEFORE loadMessagesFromFilesystem, title=${conversationTitle.value?.take(30)}")

                    // Load messages directly from filesystem API via MessageLoader
                    messageLoader.loadMessagesFromFilesystem(encodedPath, sessionId, conversationId, connectCallId)

                    DebugLogger.d(TAG, "[$connectCallId] AFTER loadMessagesFromFilesystem, title=${conversationTitle.value?.take(30)}")
                    DebugLogger.d(TAG, "[$connectCallId] currentConversationId now = ${currentConversationId?.take(20)}")

                    // Guard: Check if we're still the active conversation after async load
                    // If user switched rooms during loading, abort this connection
                    if (!sessionStateManager.isActiveConversation(conversationId)) {
                        DebugLogger.d(TAG, "[$connectCallId] !!! GUARD TRIGGERED: room switched during load, ABORTING")
                        DebugLogger.d(TAG, "[$connectCallId] BUT TITLE IS ALREADY SET TO: ${conversationTitle.value?.take(50)}")
                        return@launch
                    }
                    DebugLogger.d(TAG, "[$connectCallId] Guard passed, continuing with connection")

                    // Connect WebSocket with the full session identifier
                    // This allows continuing the conversation
                    DebugLogger.d(TAG, "[$connectCallId] Connecting WebSocket for filesystem session")
                    connectUnified(token, conversationId, sessionId, encodedPath)

                    // Subscribe to history watch for real-time file changes (after WebSocket connected)
                    DebugLogger.d(TAG, "ChatViewModel: Subscribing history watch for $encodedPath / $sessionId")
                    unifiedWebSocketClient.historySubscribe(encodedPath, sessionId)
                    DebugLogger.d(TAG, "[$connectCallId] <<< connect() COMPLETE for: $shortConvId")
                    DebugLogger.d(TAG, "[$connectCallId] Final state: title=${conversationTitle.value?.take(40)}, msgCount=${messageStore.getMessageCount()}")
                } else {
                    // Legacy database-based flow (fallback)
                    messageLoader.loadMessages(conversationId)

                    // Guard: Check if we're still the active conversation after async load
                    if (!sessionStateManager.isActiveConversation(conversationId)) {
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
                messageStore.setError(e.toUserMessage())
            }
        }
    }

    /**
     * Parses the conversationId to extract session ID and encoded path.
     * Delegated to SessionStateManager.
     * @return Pair of (sessionId, encodedPath) or (null, null) for legacy format
     */
    private fun parseConversationId(conversationId: String): Pair<String?, String?> {
        return sessionStateManager.parseConversationId(conversationId)
    }

    /**
     * Connects to the unified WebSocket and subscribes to a conversation.
     * Delegated to ConnectionManager.
     */
    private suspend fun connectUnified(
        token: String,
        conversationId: String,
        sessionId: String?,
        encodedPath: String?
    ) {
        connectionManager.connectUnified(token, conversationId, sessionId, encodedPath)
    }

    /**
     * Subscribes to history watch for real-time file change notifications via unified WebSocket.
     * Delegated to ConnectionManager.
     */
    private suspend fun connectHistoryWatch(conversationId: String, token: String) {
        connectionManager.connectHistoryWatch(conversationId, token)
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
        if (sessionStateManager.isDraftSessionValue()) {
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
                        if (isStreamingValue != stateResponse.isStreaming) {
                            DebugLogger.d(TAG, "ChatViewModel: Foreground sync - updating streaming state: ${stateResponse.isStreaming}")
                            messageStore.setStreaming(stateResponse.isStreaming)
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
                        stateResponse.todos.takeIf { it.isNotEmpty() }?.let { todos ->
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
                    if (isStreamingValue) {
                        DebugLogger.d(TAG, "ChatViewModel: Skipping message sync - streaming is active")
                        return@launch
                    }

                    // Skip message sync for draft sessions or sessions with no messages yet
                    // (new conversations don't need to reload messages)
                    if (sessionStateManager.isDraftSessionValue() || currentMessages.isEmpty()) {
                        DebugLogger.d(TAG, "ChatViewModel: Skipping message sync - draft session or no messages yet")
                        return@launch
                    }

                    // Reload messages from filesystem via MessageLoader
                    DebugLogger.d(TAG, "ChatViewModel: Syncing messages on foreground for $encodedPath / $sessionId")
                    messageLoader.loadMessagesFromFilesystem(encodedPath, sessionId, convId, "SYNC")
                } else {
                    // Legacy flow - reload via conversation API
                    DebugLogger.d(TAG, "ChatViewModel: Syncing messages on foreground (legacy) for $convId")
                    messageLoader.loadMessages(convId)
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
                if (isStreamingValue != stateResponse.isStreaming) {
                    DebugLogger.d(TAG, "ChatViewModel: REST sync - updating streaming state: ${stateResponse.isStreaming} (was: $isStreamingValue)")
                    messageStore.setStreaming(stateResponse.isStreaming)
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
                val todos = stateResponse.todos
                if (!todos.isNullOrEmpty()) {
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
                        val updated = progressTracker.updateTodos(convId, todoItems)
                        if (updated) {
                            DebugLogger.d(TAG, "ChatViewModel: REST sync - updating todos: ${todoItems.size} items")
                        }
                    }
                }

                DebugLogger.d(TAG, "ChatViewModel: REST sync complete - state=${stateResponse.sessionState}, streaming=${stateResponse.isStreaming}, todos=${todos.size}")

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
        // Unsubscribe via connection manager (handles WS operations)
        connectionManager.unsubscribe()
        // Clear session state locally
        sessionStateManager.clearSessionIdentity()
    }

    /**
     * Retries the connection after reconnection attempts have been exhausted.
     * Resets the reconnection state and attempts to connect again.
     */
    fun retryConnection() {
        connectionManager.retryConnection()
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
        DebugLogger.d(TAG, "sendMessage(): attachedImages=${images.size}, isDraft=${isDraftSession.value}, connectionState=${connectionState.value}, isStreaming=$isStreamingValue")

        if (content.isBlank() && images.isEmpty()) {
            DebugLogger.d(TAG, "sendMessage(): EARLY RETURN - content blank and no images")
            return
        }

        // Clear attached images immediately after capturing them
        _attachedImages.value = emptyList()

        // For draft sessions, we need to create the session first
        if (sessionStateManager.isDraftSessionValue()) {
            DebugLogger.d(TAG, "sendMessage(): Draft session - calling createSessionAndSendMessage()")
            scope.launch {
                createSessionAndSendMessage(content, images)
            }
            return
        }

        if (connectionState.value != ConnectionState.Connected) {
            DebugLogger.w(TAG, "sendMessage(): EARLY RETURN - not connected!")
            messageStore.setError("Not connected")
            return
        }

        // If streaming is in progress, queue the message on the server (with local fallback)
        // Claude Code CLI will handle the queuing on its side
        // Both text and image messages can be queued
        if (isStreamingValue) {
            DebugLogger.d(TAG, "sendMessage(): Streaming in progress - queueing message")
            if (queueManager.isQueueFull(_currentConversationIdFlow.value)) {
                messageStore.setError("Message queue is full (${QueueManager.MAX_QUEUED_MESSAGES} messages). Please wait for current response to complete.")
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
                messageStore.triggerScrollToBottom()

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
                        messageStore.setError("Failed to send queued message: ${e.message}")
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
            messageStore.setError("No project path available")
            return
        }

        try {
            // Generate a new session ID
            val newSessionId = kotlin.uuid.Uuid.random().toString()
            DebugLogger.d(TAG, "ChatViewModel: Creating session from draft: $newSessionId for $encodedPath")

            // Update internal state - set full session identity
            val newConversationId = "$newSessionId?project=$encodedPath"
            sessionStateManager.setSessionIdentity(newConversationId, encodedPath, newSessionId)

            // Mark as no longer draft
            sessionStateManager.setDraftSession(false)

            // Set title based on first message (truncated)
            val title = content.take(50).let { if (content.length > 50) "$it..." else it }
            sessionStateManager.setConversationTitle(title)

            // Get auth token and connect WebSocket
            val token = apiClient.getAuthToken() ?: run {
                messageStore.setError("Not authenticated")
                sessionStateManager.setDraftSession(true)  // Revert to draft mode
                return
            }

            // Connect WebSocket with the new session ID
            DebugLogger.d(TAG, "ChatViewModel: Connecting WebSocket for new session: $newConversationId")
            connectUnified(token, newConversationId, newSessionId, encodedPath)

            // Apply bypass mode if enabled in preferences (for new sessions)
            val bypassEnabled = preferencesRepository.getBoolean(PreferenceKeys.BYPASS_DEFAULT, true)
            if (bypassEnabled) {
                DebugLogger.d(TAG, "ChatViewModel: Applying bypass mode for new session (preference enabled)")
                setOperationMode(OperationMode.BYPASS)
            } else {
                DebugLogger.d(TAG, "ChatViewModel: Using default mode for new session (bypass preference disabled)")
            }

            // Subscribe to history watch for real-time updates (after WebSocket connected)
            DebugLogger.d(TAG, "ChatViewModel: Subscribing history watch for new session: $encodedPath / $newSessionId")
            unifiedWebSocketClient.historySubscribe(encodedPath, newSessionId)

            // Now send the message with images
            sendMessageInternal(content, images)

            // Defer session created event until first server response (STREAM message)
            // HistoryWatch serves as fallback for edge cases where STREAM may be missed
            DebugLogger.d(TAG, "ChatViewModel: Session created, deferring event until first response: $newSessionId")
            sessionStateManager.setPendingSessionCreatedEmit(
                SessionStateManager.SessionCreatedInfo(newSessionId, encodedPath)
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.d(TAG, "ChatViewModel: Failed to create session from draft: ${e.message}")
            messageStore.setError(e.toUserMessage())
            sessionStateManager.setDraftSession(true)  // Revert to draft mode
        }
    }

    /**
     * Clears the session created event after it has been consumed by the UI.
     */
    fun clearSessionCreatedEvent() {
        sessionStateManager.clearSessionCreatedEvent()
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
            val messageId = messageStore.generateMessageId()
            val userMessage = ChatMessage(
                id = messageId,
                role = MessageRole.USER,
                blocks = blocks,
                isStreaming = false,
                isPending = true  // Mark as pending until confirmed by history watch
            )

            // Track this pending user message to prevent duplicate from history watch
            // Use normalized content hash to handle whitespace differences in serialization
            val normalizedHash = normalizeForComparison(content).hashCode()
            DebugLogger.d(TAG, "Added pending user message (hash=$normalizedHash, images=${images.size}): ${content.take(50)}...")

            // Add pending message with tracking via MessageStore
            messageStore.addPendingMessage(userMessage, normalizedHash)
            DebugLogger.d(TAG, "Added user message id=${userMessage.id}, total=${messageStore.getMessageCount()}")

            // Trigger scroll to bottom for the new user message
            messageStore.triggerScrollToBottom()

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

            // Prepare for streaming response
            messageStore.setStreaming(true)

            // Start progress tracking for status line
            startProgressTracking("Processing")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messageStore.setError(e.toUserMessage())
            messageStore.setStreaming(false)
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
                messageStore.setError(e.toUserMessage())
            }
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        messageStore.clearError()
    }

    /**
     * Sets the operation mode for the current conversation.
     * Sends the mode change to the server via WebSocket.
     *
     * @param mode The new operation mode to set
     */
    fun setOperationMode(mode: OperationMode) {
        if (connectionState.value != ConnectionState.Connected) {
            DebugLogger.w(TAG, "Cannot change mode - not connected")
            return
        }
        scope.launch {
            try {
                unifiedWebSocketClient.sendModeChange(mode)
                DebugLogger.d(TAG, "Mode change sent: $mode")
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Failed to send mode change: ${e.message}")
                messageStore.setError("Failed to change mode: ${e.message}")
            }
        }
    }

    /**
     * Cycles through operation modes: DEFAULT -> PLAN -> BYPASS -> DEFAULT
     */
    fun cycleOperationMode() {
        val currentMode = operationMode.value
        val nextMode = currentMode.next()
        setOperationMode(nextMode)
    }

    /**
     * Clears all messages from the chat.
     */
    fun clearMessages() {
        scope.launch {
            messageStore.setMessages(emptyList())
        }
    }

    /**
     * Loads available commands from the server for the current project.
     * Delegates to CommandExecutor.
     *
     * @param projectPath Path to the project for project-level commands (optional, uses currentProjectPath if empty)
     */
    fun loadCommands(projectPath: String? = null) {
        commandExecutor.loadCommands(projectPath ?: currentProjectPath)
    }

    /**
     * Executes a slash command via the API.
     * Delegates to CommandExecutor.
     *
     * @param commandName The command name (e.g., "/help")
     * @param commandPath Optional path for custom commands
     * @param args Command arguments
     * @param onBuiltinResult Callback for builtin command result handling (for custom UI actions)
     */
    fun executeCommand(
        commandName: String,
        commandPath: String? = null,
        args: List<String> = emptyList(),
        onBuiltinResult: (ExecuteCommandResponse) -> Unit = {}
    ) {
        commandExecutor.executeCommand(
            commandName = commandName,
            commandPath = commandPath,
            args = args,
            projectPath = currentProjectPath,
            onBuiltinResult = onBuiltinResult
        )
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
                    messageStore.setMessages(emptyList())
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
                messageStore.setMessages(emptyList())
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
                // If we receive stream chunks, ensure streaming state is active
                // This handles reconnection scenarios where ViewModel was recreated
                val wasNotStreaming = messageStore.setStreamingIfNotActive()
                // Start progress tracking if streaming just started (from WebSocket reconnection)
                if (wasNotStreaming) {
                    startProgressTracking("Processing")
                }

                // Emit session created event on first stream response
                // This ensures sidebar refresh happens when server actually starts responding,
                // not waiting for HistoryWatch filesystem detection which can be delayed
                sessionStateManager.sessionCreatedMutex.withLock {
                    sessionStateManager.consumePendingSessionCreatedEmitUnsafe()?.let { sessionInfo ->
                        DebugLogger.d(TAG, "ChatViewModel: First STREAM message received, emitting session created event: ${sessionInfo.sessionId}")
                        sessionStateManager.emitSessionCreatedEvent(sessionInfo)
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
                messageStore.setError(message.error ?: "Unknown error")
                messageStore.setStreaming(false)
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
     * Finalizes streaming state when streaming completes.
     * Delegates to HistoryWatchHandler for consistent streaming finalization.
     */
    private suspend fun finalizeStreamingMessage() {
        historyWatchHandler.finalizeStreamingMessage {
            queueManager.clearSentQueueMessages(_currentConversationIdFlow.value)
        }
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
