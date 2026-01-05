package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.model.ClaudeMessage
import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.util.DebugLogger
import com.claudecode.native.util.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles message loading operations from various sources.
 *
 * This class is responsible for:
 * - Loading messages from filesystem-based Claude history API
 * - Loading messages from database-backed conversations
 * - Pagination for loading older messages
 * - Converting raw ClaudeMessages to ChatMessages
 * - Coordinating with ToolTracker for tool use/result matching
 *
 * Thread Safety:
 * - Uses guard checks via SessionStateManager to prevent race conditions
 * - Delegates message storage to MessageStore which is thread-safe
 */
class MessageLoader(
    private val claudeHistoryApi: ClaudeHistoryApi,
    private val conversationApi: ConversationApi,
    private val projectApi: ProjectApi,
    private val messageStore: MessageStore,
    private val sessionStateManager: SessionStateManager,
    private val toolTracker: ToolTracker,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MessageLoader"

        /**
         * Normalizes message content for comparison during deduplication.
         * Delegates to MessageParser for consistent normalization across the codebase.
         *
         * Handles serialization differences where adjacent text blocks might be:
         * - A single string like "Hello\n\nWorld"
         * - Multiple text blocks that join to "Hello\n\nWorld"
         */
        fun normalizeForComparison(content: String): String {
            return MessageParser.normalizeForComparison(content)
        }
    }

    // Callback for command loading (set by ChatViewModel)
    var onLoadCommands: ((String) -> Unit)? = null

    // Callback for clearing session state (set by ChatViewModel)
    // Suspend callback since clearSessionState is a suspend function
    var onClearSessionState: (suspend () -> Unit)? = null

    // ============================================================================
    // Public Loading Methods
    // ============================================================================

    /**
     * Loads more (older) messages for pagination.
     * Called when user scrolls to the top of the message list.
     */
    fun loadMoreMessages() {
        // Guard: Don't load if already loading or no more messages
        if (messageStore.isLoadingMore.value || !messageStore.hasMoreMessages.value) {
            DebugLogger.d(TAG, "loadMoreMessages skipped - isLoading=${messageStore.isLoadingMore.value}, hasMore=${messageStore.hasMoreMessages.value}")
            return
        }

        val encodedPath = sessionStateManager.currentEncodedPath ?: return
        val sessionId = sessionStateManager.currentClaudeSession ?: return
        val expectedConversationId = sessionStateManager.currentConversationId ?: return

        scope.launch {
            messageStore.setLoadingMore(true)
            try {
                val currentOffset = messageStore.currentMessagesOffset
                DebugLogger.d(TAG, "Loading more messages, offset=$currentOffset")
                val response = claudeHistoryApi.getSessionMessages(
                    encodedPath = encodedPath,
                    sessionId = sessionId,
                    limit = 100,
                    offset = currentOffset,
                    summary = false
                )

                // GUARD CHECK: Verify we're still in the same conversation
                if (!sessionStateManager.isActiveConversation(expectedConversationId)) {
                    DebugLogger.d(TAG, "Room switched during loadMore, discarding results")
                    return@launch
                }

                if (response.messages.isEmpty()) {
                    messageStore.setHasMoreMessages(false)
                    return@launch
                }

                DebugLogger.d(TAG, "Got ${response.messages.size} more messages, hasMore=${response.hasMore}")

                // Update pagination state via MessageStore
                messageStore.updatePaginationState(response.messages.size, response.hasMore)

                // Process and prepend older messages
                processOlderMessages(response.messages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Failed to load more messages: ${e.message}", e)
                messageStore.setError(e.toUserMessage())
            } finally {
                messageStore.setLoadingMore(false)
            }
        }
    }

    /**
     * Loads messages directly from filesystem-based Claude history API.
     * Also fetches project info to store the original project path and session title.
     *
     * @param encodedPath Encoded project path for API calls
     * @param sessionId Claude session ID
     * @param expectedConversationId Expected conversation ID for guard checks
     * @param callId Identifier for logging (e.g., "INIT", "SYNC")
     */
    suspend fun loadMessagesFromFilesystem(
        encodedPath: String,
        sessionId: String,
        expectedConversationId: String,
        callId: String = "?"
    ) {
        try {
            DebugLogger.d(TAG, "[$callId] loadMessagesFromFilesystem START - sessionId=${sessionId.take(15)}")

            // Fetch project to get the original path and session title for delete operations
            try {
                val project = claudeHistoryApi.getProject(encodedPath)

                // GUARD CHECK: Before setting ANY state, verify we're still the active conversation
                if (!sessionStateManager.isActiveConversation(expectedConversationId)) {
                    DebugLogger.d(TAG, "[$callId] !!! GUARD: Room switched during project fetch, aborting")
                    return
                }

                sessionStateManager.setProjectPath(project.path)
                DebugLogger.d(TAG, "[$callId] Got project: ${project.name}")

                // Find the session and extract the title (firstMessage)
                val session = project.sessions.find { it.id == sessionId }
                val newTitle = if (session != null && session.firstMessage.isNotBlank()) {
                    session.firstMessage
                } else {
                    project.name
                }

                // GUARD CHECK again before setting title
                if (!sessionStateManager.isActiveConversation(expectedConversationId)) {
                    DebugLogger.d(TAG, "[$callId] !!! GUARD: Room switched before title set, aborting")
                    return
                }

                sessionStateManager.setConversationTitle(newTitle)
                DebugLogger.d(TAG, "[$callId] Title set to: ${newTitle.take(50)}")

                // Load commands now that we have the project path
                onLoadCommands?.invoke(project.path)
            } catch (e: Exception) {
                DebugLogger.d(TAG, "[$callId] Failed to fetch project info: ${e.message}")
                sessionStateManager.setConversationTitle(null)
            }

            // GUARD CHECK before loading messages
            if (!sessionStateManager.isActiveConversation(expectedConversationId)) {
                DebugLogger.d(TAG, "[$callId] !!! GUARD: Room switched before message load, aborting")
                return
            }

            // Load messages from file-based API
            try {
                val response = claudeHistoryApi.getSessionMessages(
                    encodedPath = encodedPath,
                    sessionId = sessionId,
                    limit = 100,
                    offset = 0,
                    summary = false
                )

                // GUARD CHECK after API call, before setting messages
                if (!sessionStateManager.isActiveConversation(expectedConversationId)) {
                    DebugLogger.d(TAG, "[$callId] !!! GUARD: Room switched during message fetch, discarding ${response.messages.size} messages")
                    return
                }

                DebugLogger.d(TAG, "[$callId] Got ${response.messages.size} messages from API, hasMore=${response.hasMore}")

                // Only clear state on initial load, not on foreground sync
                if (callId != "SYNC") {
                    onClearSessionState?.invoke()
                }

                // Set pagination state via MessageStore
                messageStore.setPaginationState(response.messages.size, response.hasMore)

                // Process messages
                processLoadedMessages(response.messages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleLoadError(e, expectedConversationId, callId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.d(TAG, "Failed during filesystem message loading: ${e.message}")
            messageStore.setError(e.toUserMessage())
        }
    }

    /**
     * Loads messages from database-backed conversation.
     * Uses conversationApi to get session info, then loads from filesystem.
     *
     * @param conversationId Database conversation ID
     */
    suspend fun loadMessages(conversationId: String) {
        try {
            // Get conversation details to find claudeSession and project
            val conversation = conversationApi.getConversation(conversationId)

            // Set conversation title from database
            sessionStateManager.setConversationTitle(conversation.title)

            val claudeSession = conversation.claudeSession
            if (claudeSession.isNullOrBlank()) {
                DebugLogger.d(TAG, "No claudeSession for conversation $conversationId")
                return
            }

            // Get project to find the path
            val project = projectApi.getProject(conversation.projectId)
            val encodedPath = encodeProjectPath(project.path)

            // Store project path for delete operations
            sessionStateManager.setProjectPath(project.path)

            // Load commands now that we have the project path
            onLoadCommands?.invoke(project.path)

            DebugLogger.d(TAG, "Loading messages from $encodedPath / $claudeSession")

            // Load messages from file-based API
            val response = claudeHistoryApi.getSessionMessages(
                encodedPath = encodedPath,
                sessionId = claudeSession,
                limit = 100,
                offset = 0,
                summary = false
            )
            DebugLogger.d(TAG, "Got ${response.messages.size} messages from API")

            // Use shared processing logic
            processLoadedMessages(response.messages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 404 Not Found is expected for new conversations without history
            val isNotFound = e.message?.contains("Not found", ignoreCase = true) == true ||
                    e.message?.contains("404", ignoreCase = true) == true
            if (isNotFound) {
                DebugLogger.d(TAG, "No history found for conversation (this is normal for new chats)")
                onClearSessionState?.invoke()
            } else {
                DebugLogger.d(TAG, "Failed to load messages: ${e.message}")
                messageStore.setError("Failed to load messages: ${e.message}")
            }
        }
    }

    // ============================================================================
    // Message Processing Methods
    // ============================================================================

    /**
     * Processes loaded ClaudeMessages into ChatMessages and updates the UI.
     * Two-pass parsing to match tool_use with tool_result across messages.
     */
    suspend fun processLoadedMessages(messages: List<ClaudeMessage>) {
        // IMPORTANT: Do NOT sort by timestamp!
        // The backend returns messages in correct chronological order from the JSONL file.
        // Sorting by timestamp breaks the order because:
        // 1. Multiple messages can have identical timestamps
        // 2. Kotlin's sortedWith() doesn't guarantee stable ordering for equal elements
        // Trust the file order - it's the source of truth.

        // Clear and rebuild session-level tool tracking via ToolTracker
        toolTracker.extractAndTrackTools(messages)

        // Get snapshot of matched tool uses for message processing
        val allToolUses = toolTracker.getMatchedToolUses()

        // Pass 2: Create chat messages with matched tools (using original file order)
        val chatMessages = messages.mapIndexedNotNull { index, msg ->
            convertClaudeMessageToChatMessage(msg, index, allToolUses)
        }
        DebugLogger.d(TAG, "Converted to ${chatMessages.size} chat messages")

        // Use MessageStore's mergeMessages for thread-safe update
        messageStore.mergeMessages(chatMessages) { currentList, newMessages ->
            mergeWithDeduplication(currentList, newMessages)
        }
    }

    /**
     * Processes older messages and prepends them to the existing message list.
     * Uses same logic as processLoadedMessages for consistency.
     */
    private suspend fun processOlderMessages(messages: List<ClaudeMessage>) {
        // Build tool maps for older messages
        val olderToolUses = mutableMapOf<String, ToolUseInfo>()
        val olderToolResults = mutableMapOf<String, Pair<String, Boolean>>()

        for (msg in messages) {
            val message = msg.message ?: continue
            toolTracker.extractToolsToMaps(message.content, olderToolUses, olderToolResults)
        }

        // Match tool results to tool uses
        toolTracker.matchResultsInMaps(olderToolUses, olderToolResults)

        // Merge older tool uses into session tracker
        toolTracker.mergeAndMatchTools(olderToolUses, olderToolResults)

        // Convert older messages to ChatMessages
        val olderChatMessages = messages.mapIndexedNotNull { index, msg ->
            val message = msg.message ?: return@mapIndexedNotNull null
            val role = message.role
            val blocks = parseMessageContent(message.content, msg.uuid)

            // Update tool blocks with results
            val blocksWithResults = toolTracker.updateBlocksWithToolResultsSync(blocks, olderToolUses)

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

        if (olderChatMessages.isEmpty()) {
            DebugLogger.d(TAG, "No older messages to prepend after filtering")
            return
        }

        // Prepend older messages to existing list via MessageStore
        messageStore.mergeMessages(olderChatMessages) { currentList, newMessages ->
            // Build ID set for deduplication
            val existingIds = currentList.map { it.id }.toSet()
            val uniqueOlderMessages = newMessages.filter { it.id !in existingIds }

            // Prepend older messages
            currentList.addAll(0, uniqueOlderMessages)
            DebugLogger.d(TAG, "Prepended ${uniqueOlderMessages.size} older messages")
        }
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    /**
     * Converts a ClaudeMessage to ChatMessage.
     */
    private fun convertClaudeMessageToChatMessage(
        msg: ClaudeMessage,
        index: Int,
        allToolUses: Map<String, ToolUseInfo>
    ): ChatMessage? {
        val message = msg.message ?: return null
        val role = message.role
        val blocks = parseMessageContent(message.content, msg.uuid)

        // Update tool blocks with results from session maps
        val blocksWithResults = toolTracker.updateBlocksWithToolResultsSync(blocks, allToolUses)

        // Skip tool_result-only messages (they're matched to tool_use messages)
        if (hasOnlyToolResults(message.content)) {
            return null
        }

        // Skip meta messages (skill content injected by Claude Code, not user-typed)
        if (msg.isMeta) {
            return null
        }

        // Skip messages with no content blocks
        if (blocksWithResults.isEmpty()) return null

        // Get text content for validation
        val textContent = blocksWithResults.filterIsInstance<ContentBlock.Text>()
            .joinToString("\n\n") { it.content }

        // Skip compaction/summary messages (system-generated, not user content)
        if (isCompactionMessage(textContent)) return null

        return ChatMessage(
            id = msg.uuid ?: "msg_${msg.timestamp?.toEpochMilliseconds() ?: index}",
            role = when (role) {
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                else -> return null
            },
            blocks = blocksWithResults,
            isStreaming = false,
            gitBranch = msg.gitBranch,
            agentId = msg.agentId,
            isSidechain = msg.isSidechain
        )
    }

    /**
     * Merges new messages with existing messages, handling deduplication.
     */
    private fun mergeWithDeduplication(
        currentList: MutableList<ChatMessage>,
        newMessages: List<ChatMessage>
    ) {
        // Preserve pending messages that haven't been confirmed by history watch yet
        val pendingMessages = currentList.filter { it.isPending }
        val messagesToAdd = mutableListOf<ChatMessage>()

        if (pendingMessages.isNotEmpty()) {
            DebugLogger.d(TAG, "Preserving ${pendingMessages.size} pending messages during reload")
            val loadedContentHashes = newMessages.map { it.content.hashCode() }.toSet()
            val uniquePendingMessages = pendingMessages.filter { pending ->
                pending.content.hashCode() !in loadedContentHashes
            }
            messagesToAdd.addAll(uniquePendingMessages)
        }

        // Build a map of (normalized content + role + approximate position) -> existing ID
        val existingByContentAndRole = currentList.groupBy { msg ->
            "${msg.role}_${normalizeForComparison(msg.content)}"
        }

        // Track which existing IDs have been used to avoid duplicates
        val usedIds = mutableSetOf<String>()

        // Update loaded messages to preserve existing IDs where content matches
        val contentRoleCounters = mutableMapOf<String, Int>()
        val stableMessages = newMessages.map { msg ->
            val key = "${msg.role}_${normalizeForComparison(msg.content)}"
            val existingList = existingByContentAndRole[key]

            if (existingList != null && existingList.isNotEmpty()) {
                val occurrenceIndex = contentRoleCounters.getOrPut(key) { 0 }
                contentRoleCounters[key] = occurrenceIndex + 1

                val existing = existingList.getOrNull(occurrenceIndex)
                if (existing != null && existing.id !in usedIds) {
                    usedIds.add(existing.id)
                    msg.copy(id = existing.id)
                } else {
                    usedIds.add(msg.id)
                    msg
                }
            } else {
                usedIds.add(msg.id)
                msg
            }
        }

        // Final deduplication by ID
        val allMessages = stableMessages + messagesToAdd
        val deduplicatedMessages = allMessages
            .associateBy { it.id }
            .values
            .toList()

        // Clear and set new messages
        currentList.clear()
        currentList.addAll(deduplicatedMessages)
        DebugLogger.d(TAG, "Added ${messagesToAdd.size} messages (initial load), total=${deduplicatedMessages.size}")
    }

    /**
     * Handles load errors with proper error classification.
     */
    private suspend fun handleLoadError(e: Exception, expectedConversationId: String, callId: String) {
        val isExpectedError = e.message?.contains("Not found", ignoreCase = true) == true ||
                e.message?.contains("404", ignoreCase = true) == true ||
                e.message?.contains("resource not found", ignoreCase = true) == true ||
                e.message?.contains("Bad Request", ignoreCase = true) == true ||
                e.message?.contains("400", ignoreCase = true) == true ||
                e.message?.contains("session not found", ignoreCase = true) == true

        if (isExpectedError) {
            DebugLogger.d(TAG, "[$callId] No history found for session (this is normal for new chats)")

            // GUARD CHECK: Verify we're still the active conversation before clearing state
            if (!sessionStateManager.isActiveConversation(expectedConversationId)) {
                DebugLogger.d(TAG, "[$callId] !!! GUARD: Room switched during 404 handling, aborting state clear")
                return
            }

            // Clear old state for new sessions
            onClearSessionState?.invoke()
            DebugLogger.d(TAG, "[$callId] Cleared state for new session")
        } else {
            DebugLogger.d(TAG, "[$callId] Failed to load messages from filesystem: ${e.message}")
            messageStore.setError(e.toUserMessage())
        }
    }

    /**
     * Encodes a project path for Claude history API.
     * Delegates to SessionStateManager.encodeProjectPath.
     */
    fun encodeProjectPath(path: String): String {
        return SessionStateManager.encodeProjectPath(path)
    }

    // Message parsing delegated to MessageParser utility object
    private fun parseMessageContent(content: Any?, messageUuid: String? = null) =
        MessageParser.parseMessageContent(content, messageUuid)

    private fun hasOnlyToolResults(content: Any?) = MessageParser.hasOnlyToolResults(content)
    private fun isCompactionMessage(text: String) = MessageParser.isCompactionMessage(text)
}
