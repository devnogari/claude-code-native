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
import com.claudecode.native.ui.component.ProgressStatus
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

    private val _queuedMessages = MutableStateFlow<List<QueuedMessage>>(emptyList())
    /** Messages queued while streaming is in progress. */
    val queuedMessages: StateFlow<List<QueuedMessage>> = _queuedMessages.asStateFlow()

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

    // Per-conversation progress tracking (Map: conversationId -> ProgressStatus)
    // Note: Map access is safe because all operations occur on the main coroutine dispatcher
    // via viewModelScope, which is single-threaded. No mutex needed.
    private val _progressStatusMap = mutableMapOf<String, MutableStateFlow<ProgressStatus>>()

    // Per-conversation elapsed time tracking jobs (Map: conversationId -> Job)
    private val elapsedTimeJobs = mutableMapOf<String, Job>()

    // Per-conversation streaming start times (Map: conversationId -> timestamp)
    private val streamingStartTimes = mutableMapOf<String, Long>()

    // Static fallback for when no conversation is selected (avoids creating new StateFlow each access)
    private val _inactiveProgressStatus = MutableStateFlow(ProgressStatus()).asStateFlow()

    /**
     * Progress status for the current conversation's status line UI (Claude Code style).
     * Tracks elapsed time, status text, tokens, and thinking time during streaming.
     * Returns the status for currentConversationId, or inactive status if none.
     */
    val progressStatus: StateFlow<ProgressStatus>
        get() = currentConversationId?.let { convId ->
            _progressStatusMap.getOrPut(convId) { MutableStateFlow(ProgressStatus()) }
        }?.asStateFlow() ?: _inactiveProgressStatus

    /** Connection state exposed from the WebSocket client. */
    val connectionState: StateFlow<ConnectionState> = webSocketClient.connectionState

    private var currentConversationId: String? = null
    private var _streamingMessageId: String? = null
    private var streamingMessageId: String?
        get() = _streamingMessageId
        set(value) { _streamingMessageId = value }

    /** Current streaming message ID for inspection */
    val currentStreamingMessageId: String? get() = _streamingMessageId
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
        /** Marker for draft sessions that haven't been created yet. */
        const val DRAFT_SESSION_MARKER = "draft"
        /** Number of attempts to wait for WebSocket connection. */
        private const val CONNECTION_TIMEOUT_ATTEMPTS = 50
        /** Interval between connection checks in milliseconds. */
        private const val CONNECTION_CHECK_INTERVAL_MS = 100L
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

                        // FIX: Clear previous session's messages for draft sessions
                        clearSessionState()
                        println("ChatViewModel: Cleared previous session state for draft")

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
                clearSessionState()

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

                    // Clear old state for new sessions
                    clearSessionState()
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

            // Final deduplication by ID - O(n) using LinkedHashMap to preserve order
            val allMessages = stableMessages + messagesToAdd
            val deduplicatedMessages = allMessages
                .associateBy { it.id }  // Keeps last occurrence, preserves insertion order
                .values
                .toList()

            _messages.value = deduplicatedMessages
            println("ChatViewModel:697 - Added ${messagesToAdd.size} messages (initial load), total=${_messages.value.size}")
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

                // Clear old state for new sessions
                clearSessionState()
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
        // Use atomic update to prevent race conditions with concurrent queue operations
        if (_isStreaming.value) {
            var wasQueueFull = false
            _queuedMessages.update { queue ->
                if (queue.size >= maxQueuedMessages) {
                    wasQueueFull = true
                    queue  // Return unchanged
                } else {
                    val queuedMessage = QueuedMessage(
                        id = generateMessageId(),
                        content = content,
                        queuedAt = Clock.System.now().toEpochMilliseconds(),
                        source = QueuedMessageSource.LOCAL
                    )
                    println("ChatViewModel: Queued message while streaming (id=${queuedMessage.id}): ${content.take(50)}...")
                    queue + queuedMessage
                }
            }
            if (wasQueueFull) {
                _error.value = "Message queue is full ($maxQueuedMessages messages). Please wait for current response to complete."
            }
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
                println("ChatViewModel:1054 - Added user message id=${userMessage.id}, total=${_messages.value.size}")
            }

            // Trigger scroll to bottom for the new user message (atomic update)
            _scrollToBottomSignal.update { it + 1 }

            // Send via WebSocket
            webSocketClient.sendChat(content)

            // Prepare for streaming response (use streamingMutex for consistency with other handlers)
            // Note: streamingMessageId will be set by HistoryWatch when it receives the actual message UUID
            streamingMutex.withLock {
                _isStreaming.value = true
                _streamingContent.value = ""
                _streamingTools.value = emptyList()
                _streamingBlocks.value = emptyList()
                // Don't generate ID here - HistoryWatch will provide the actual message UUID
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
        }
    }

    /**
     * Processes the next queued message, if any.
     * Called after streaming completes.
     * Uses atomic update to prevent race conditions.
     */
    private suspend fun processNextQueuedMessage() {
        var nextMessageContent: String? = null
        _queuedMessages.update { queue ->
            if (queue.isNotEmpty()) {
                val nextMessage = queue.first()
                nextMessageContent = nextMessage.content
                println("ChatViewModel: Processing queued message (id=${nextMessage.id}): ${nextMessage.content.take(50)}...")
                queue.drop(1)
            } else {
                queue
            }
        }
        // Send outside of update to avoid nested state modifications
        nextMessageContent?.let { sendMessageInternal(it) }
    }

    /**
     * Cancels a specific queued message by its ID.
     * Only LOCAL messages can be cancelled from this app.
     * Uses atomic update to prevent race conditions.
     *
     * @param messageId The ID of the queued message to cancel
     */
    fun cancelQueuedMessage(messageId: String) {
        var errorMessage: String? = null
        _queuedMessages.update { queue ->
            val message = queue.find { it.id == messageId }
            when {
                message == null -> {
                    println("ChatViewModel: Cannot cancel - message not found: $messageId")
                    queue  // Return unchanged
                }
                message.source == QueuedMessageSource.CLI -> {
                    println("ChatViewModel: Cannot cancel CLI message from app: $messageId")
                    errorMessage = "Cannot cancel messages queued from terminal"
                    queue  // Return unchanged
                }
                else -> {
                    println("ChatViewModel: Cancelled queued message: $messageId")
                    queue.filter { it.id != messageId }
                }
            }
        }
        // Set error outside of update to avoid nested state modifications
        errorMessage?.let { _error.value = it }
    }

    /**
     * Clears all locally queued messages.
     * CLI-originated messages cannot be cleared from this app.
     * Uses atomic update to prevent race conditions.
     */
    fun clearLocalQueuedMessages() {
        _queuedMessages.update { queue ->
            val cliMessages = queue.filter { it.source == QueuedMessageSource.CLI }
            val localCount = queue.size - cliMessages.size
            if (localCount > 0) {
                println("ChatViewModel: Cleared $localCount locally queued messages")
                cliMessages
            } else {
                queue
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
                            println("ChatViewModel:1231 - Added builtin result id=${resultMessage.id}, total=${_messages.value.size}")
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
                        // Note: Don't generate temporary ID here
                        // streamingMessageId will be set by HistoryWatch when it receives the actual message UUID
                        // This ensures streaming bubble only shows when we have a valid UUID
                        true
                    } else {
                        false
                    }
                }
                // Start progress tracking if streaming just started (from WebSocket reconnection)
                if (wasNotStreaming) {
                    startProgressTracking("Processing")
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
                // Use atomic updates to prevent race conditions with concurrent queue operations
                for (claudeMsg in event.messages) {
                    if (claudeMsg.type == "queue-operation") {
                        val operation = claudeMsg.operation
                        val queueContent = claudeMsg.content ?: continue

                        when (operation) {
                            "enqueue" -> {
                                // Add to queued messages (from Claude CLI's perspective)
                                // Use UUID-style ID to avoid timestamp collision
                                val cliQueuedMessage = QueuedMessage(
                                    id = "cli_${generateMessageId()}",
                                    content = queueContent,
                                    queuedAt = Clock.System.now().toEpochMilliseconds(),
                                    source = QueuedMessageSource.CLI
                                )
                                _queuedMessages.update { queue -> queue + cliQueuedMessage }
                                println("ChatViewModel: Queued message from CLI (id=${cliQueuedMessage.id}): ${queueContent.take(50)}...")
                            }
                            "dequeue", "clear" -> {
                                // Remove from queued messages (CLI processes in order)
                                _queuedMessages.update { queue ->
                                    if (queue.isNotEmpty()) {
                                        println("ChatViewModel: Dequeued message from CLI")
                                        queue.drop(1)
                                    } else {
                                        queue
                                    }
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
                // Get the assistant message's UUID to use as streamingMessageId
                val assistantMessage = event.messages.find { it.message?.role == "assistant" }
                if (assistantMessage != null) {
                    // Synchronize streaming state changes to prevent race conditions with WebSocket
                    val shouldStartProgress = streamingMutex.withLock {
                        val wasNotStreaming = !_isStreaming.value
                        if (wasNotStreaming) {
                            println("ChatViewModel: Detected assistant activity from HistoryWatch, activating streaming state")
                            _isStreaming.value = true
                            isStreamingFromHistoryWatch = true
                        }
                        // Always update streamingMessageId with the actual message UUID from HistoryWatch
                        if (assistantMessage.uuid != null) {
                            streamingMessageId = assistantMessage.uuid
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
                        val progressFlow = _progressStatusMap[convId]
                        if (progressFlow != null) {
                            val current = progressFlow.value
                            // Only update if todos actually changed to avoid unnecessary recomposition
                            if (current.todos != event.todos) {
                                progressFlow.value = current.copy(todos = event.todos)
                                println("ChatViewModel: Updated todos (${event.todos.size} items, ${event.todos.count { it.isCompleted }} completed)")
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
                // IMPORTANT: HistoryWatch delivers messages with blocks in the correct order.
                // When an assistant message has interleaved text and tools (e.g., [Tool1, Text, Tool2]),
                // we should preserve this order. The key insight is that HistoryWatch provides the
                // complete, properly-ordered message structure from the JSONL file.
                //
                // Strategy:
                // - When HistoryWatch delivers a complete assistant message with blocks, use that order
                // - Replace streaming blocks entirely with the properly-ordered blocks from HistoryWatch
                // - This ensures real-time display matches what will be loaded on re-entry
                streamingMutex.withLock {
                    if (_isStreaming.value) {
                        var blocksUpdated = false

                        // Collect all blocks from all assistant messages in this event, preserving order
                        val allOrderedBlocks = mutableListOf<ContentBlock>()

                        for (claudeMsg in event.messages) {
                            val msg = claudeMsg.message ?: continue
                            if (msg.role != "assistant") continue

                            val blocks = parseMessageContent(msg.content, claudeMsg.uuid)
                            for (block in blocks) {
                                when (block) {
                                    is ContentBlock.Text -> {
                                        // Always include text blocks to maintain proper interleaving order
                                        // Check for duplicates by content
                                        val exists = allOrderedBlocks.any {
                                            it is ContentBlock.Text && it.content == block.content
                                        }
                                        if (!exists && block.content.isNotBlank()) {
                                            allOrderedBlocks.add(block)
                                        }
                                    }
                                    is ContentBlock.Tool -> {
                                        // Check if tool already exists, update or add
                                        val existingIndex = allOrderedBlocks.indexOfFirst {
                                            it is ContentBlock.Tool && it.info.id == block.info.id
                                        }
                                        // Lock ordering: streamingMutex (#1) → mapsMutex (#3) is allowed
                                        val toolWithResult = mapsMutex.withLock {
                                            sessionToolUses[block.info.id]
                                        } ?: block.info
                                        if (existingIndex >= 0) {
                                            allOrderedBlocks[existingIndex] = ContentBlock.Tool(toolWithResult)
                                        } else {
                                            allOrderedBlocks.add(ContentBlock.Tool(toolWithResult))
                                        }
                                    }
                                }
                            }
                            blocksUpdated = true
                        }

                        if (blocksUpdated && allOrderedBlocks.isNotEmpty()) {
                            // Merge with existing blocks: keep existing tool results, update order
                            // Also preserve any blocks in currentBlocks that aren't in allOrderedBlocks
                            // (e.g., tool results that arrived via different code path)
                            val currentBlocks = _streamingBlocks.value

                            // Start with properly-ordered blocks from HistoryWatch
                            val mergedBlocks = allOrderedBlocks.map { newBlock ->
                                when (newBlock) {
                                    is ContentBlock.Tool -> {
                                        // Check if we have a more complete version (with result) in current blocks
                                        val existing = currentBlocks.find {
                                            it is ContentBlock.Tool && it.info.id == newBlock.info.id
                                        } as? ContentBlock.Tool
                                        if (existing?.info?.result != null && newBlock.info.result == null) {
                                            existing // Keep existing if it has result
                                        } else {
                                            newBlock
                                        }
                                    }
                                    else -> newBlock
                                }
                            }.toMutableList()

                            // Append any existing tool blocks that weren't in allOrderedBlocks
                            // (preserves tool results that may have arrived separately)
                            val mergedToolIds = mergedBlocks
                                .filterIsInstance<ContentBlock.Tool>()
                                .map { it.info.id }
                                .toSet()

                            currentBlocks.filterIsInstance<ContentBlock.Tool>()
                                .filter { it.info.id !in mergedToolIds }
                                .forEach { mergedBlocks.add(it) }

                            _streamingBlocks.value = mergedBlocks
                            println("ChatViewModel: Replaced streaming blocks with ${mergedBlocks.size} properly-ordered blocks")
                        }

                        // Update _streamingContent only if HistoryWatch has more complete text
                        // WebSocket delivers text incrementally (responsive), HistoryWatch delivers
                        // complete text blocks. Only update if HistoryWatch text is longer.
                        // Performance: only compute when text blocks were actually updated
                        val hasTextBlocks = allOrderedBlocks.any { it is ContentBlock.Text }
                        if (blocksUpdated && hasTextBlocks) {
                            val historyWatchTextContent = _streamingBlocks.value
                                .filterIsInstance<ContentBlock.Text>()
                                .joinToString("\n\n") { it.content }
                            val currentWebSocketText = _streamingContent.value
                            if (historyWatchTextContent.length > currentWebSocketText.length) {
                                _streamingContent.value = historyWatchTextContent
                            }
                        }

                        // Always update tools list
                        _streamingTools.value = _streamingBlocks.value
                            .filterIsInstance<ContentBlock.Tool>()
                            .map { it.info }

                        println("ChatViewModel: Updated streaming blocks: ${_streamingBlocks.value.size} blocks (historyWatch=$isStreamingFromHistoryWatch)")
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
                            println("ChatViewModel:1742 - HistoryWatch update, total=${_messages.value.size}")
                        }
                    }
                }

                // Streaming completion is handled by WebSocket COMPLETE message
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
        // Reset streaming state - message saving is handled by HistoryWatch
        streamingMutex.withLock {
            _isStreaming.value = false
            _streamingContent.value = ""
            _streamingTools.value = emptyList()
            _streamingBlocks.value = emptyList()
            streamingMessageId = null
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

        // Process next queued message if any (outside the lock to avoid deadlock)
        processNextQueuedMessage()
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
    private fun startProgressTracking(statusText: String = "Processing") {
        val convId = currentConversationId ?: return

        streamingStartTimes[convId] = Clock.System.now().toEpochMilliseconds()

        // Get or create progress status flow for this conversation
        val progressFlow = _progressStatusMap.getOrPut(convId) { MutableStateFlow(ProgressStatus()) }

        // Initialize progress status
        progressFlow.value = ProgressStatus(
            statusText = statusText,
            elapsedSeconds = 0,
            tokenCount = null,
            thinkingSeconds = null,
            isActive = true
        )

        // Cancel existing job for this conversation if any
        elapsedTimeJobs[convId]?.cancel()

        // Start elapsed time counter job for this conversation
        elapsedTimeJobs[convId] = scope.launch {
            while (true) {
                delay(1000)
                val startTime = streamingStartTimes[convId] ?: break
                val elapsed = ((Clock.System.now().toEpochMilliseconds() - startTime) / 1000).toInt()
                progressFlow.update { current ->
                    current.copy(elapsedSeconds = elapsed)
                }
            }
        }
    }

    /**
     * Stops progress tracking and hides the status line.
     * Called when streaming ends (complete, error, or stop).
     * Stops tracking for the current conversation only.
     */
    private fun stopProgressTracking() {
        val convId = currentConversationId ?: return

        elapsedTimeJobs[convId]?.cancel()
        elapsedTimeJobs.remove(convId)
        streamingStartTimes.remove(convId)

        _progressStatusMap[convId]?.value = ProgressStatus(isActive = false)
    }

}
