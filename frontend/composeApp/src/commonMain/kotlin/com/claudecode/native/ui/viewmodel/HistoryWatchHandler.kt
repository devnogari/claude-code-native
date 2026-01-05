package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.data.model.SessionState
import com.claudecode.native.data.websocket.HistoryWatchEvent
import com.claudecode.native.util.DebugLogger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Handles HistoryWatch events from the WebSocket.
 *
 * This class is responsible for:
 * - Processing real-time file change notifications from Claude session files
 * - Converting raw ClaudeMessages to ChatMessages
 * - Managing tool use/result matching for incoming messages
 * - Queue operation handling (enqueue, dequeue, clear, remove)
 * - Streaming state detection from HistoryWatch events
 *
 * Thread Safety:
 * - Uses sessionStateManager's mutex for session event coordination
 * - Delegates message storage to MessageStore which is thread-safe
 */
@OptIn(ExperimentalEncodingApi::class)
class HistoryWatchHandler(
    private val messageStore: MessageStore,
    private val sessionStateManager: SessionStateManager,
    private val toolTracker: ToolTracker,
    private val queueManager: QueueManager,
    private val progressTracker: ProgressTracker,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "HistoryWatchHandler"

        /**
         * Normalizes message content for comparison during deduplication.
         * Delegates to MessageParser for consistent normalization across the codebase.
         */
        fun normalizeForComparison(content: String): String {
            return MessageParser.normalizeForComparison(content)
        }
    }

    // ============================================================================
    // Callbacks for ChatViewModel Integration
    // ============================================================================

    /** Callback to parse message content (uses ChatViewModel's MessageParser). */
    var onParseMessageContent: ((Any?, String?) -> List<ContentBlock>)? = null

    /** Callback to check if message has only tool results. */
    var onHasOnlyToolResults: ((Any?) -> Boolean)? = null

    /** Callback to check if text is a compaction message. */
    var onIsCompactionMessage: ((String) -> Boolean)? = null

    /** Callback to get current conversation ID flow. */
    var getCurrentConversationIdFlow: (() -> StateFlow<String?>)? = null

    /** Callback to start progress tracking. */
    var onStartProgressTracking: ((String) -> Unit)? = null

    /** Callback to stop progress tracking. */
    var onStopProgressTracking: (() -> Unit)? = null

    // ============================================================================
    // Main Event Handler
    // ============================================================================

    /**
     * Handles events from the history watch WebSocket.
     * This allows receiving real-time updates when Claude session files change,
     * enabling the app to show messages from Claude running in terminal or other sources.
     */
    suspend fun handleHistoryWatchEvent(event: HistoryWatchEvent) {
        DebugLogger.d(TAG, "handleHistoryWatchEvent: ${event::class.simpleName}")

        when (event) {
            is HistoryWatchEvent.Connected -> {
                handleConnectedEvent(event)
            }

            is HistoryWatchEvent.NewMessages -> {
                handleNewMessagesEvent(event)
            }

            is HistoryWatchEvent.Error -> {
                handleErrorEvent(event)
            }

            is HistoryWatchEvent.Disconnected -> {
                handleDisconnectedEvent()
            }

            is HistoryWatchEvent.Unsubscribed -> {
                handleUnsubscribedEvent()
            }
        }
    }

    // ============================================================================
    // Event Handlers
    // ============================================================================

    private fun handleConnectedEvent(event: HistoryWatchEvent.Connected) {
        DebugLogger.d(TAG, "History watch connected for ${event.sessionId}")
    }

    private suspend fun handleNewMessagesEvent(event: HistoryWatchEvent.NewMessages) {
        DebugLogger.d(TAG, "NewMessages event: sessionId=${event.sessionId}, " +
                "encodedPath=${event.encodedPath}, messages=${event.messages.size}, " +
                "sessionState=${event.sessionState}")

        val currentClaudeSession = sessionStateManager.currentClaudeSession
        val currentEncodedPath = sessionStateManager.currentEncodedPath

        DebugLogger.d(TAG, "Current session: claude=$currentClaudeSession, path=$currentEncodedPath")

        // Validate event belongs to current conversation to prevent stale messages
        if (event.sessionId != currentClaudeSession || event.encodedPath != currentEncodedPath) {
            DebugLogger.d(TAG, "Ignoring history watch event for stale session " +
                    "(event: ${event.sessionId}, current: $currentClaudeSession)")
            return
        }

        DebugLogger.d(TAG, "Session matched! Processing ${event.messages.size} messages")

        // Fallback: Emit session created event if not already emitted via STREAM message
        emitPendingSessionCreatedEvent(event.sessionId)

        // Handle queue-operation events
        handleQueueOperations(event)

        // Log incoming messages for debugging
        logIncomingMessages(event)

        // Check if any message is from assistant - detect streaming
        detectAssistantActivity(event)

        // Update todos in progress status
        updateTodosFromEvent(event)

        // Process tools and messages
        val newChatMessages = processMessagesWithTools(event)

        // Update existing messages with tool results
        updateExistingToolResults()

        // Merge new messages
        if (newChatMessages.isNotEmpty()) {
            mergeNewMessages(newChatMessages)
        }

        // Check session state to detect streaming completion
        checkSessionStateForCompletion(event.sessionState)
    }

    private suspend fun handleErrorEvent(event: HistoryWatchEvent.Error) {
        DebugLogger.d(TAG, "History watch error: ${event.message}")
        sessionStateManager.sessionCreatedMutex.withLock {
            sessionStateManager.setPendingSessionCreatedEmitUnsafe(null)
        }
    }

    private suspend fun handleDisconnectedEvent() {
        DebugLogger.d(TAG, "History watch disconnected")
        sessionStateManager.sessionCreatedMutex.withLock {
            sessionStateManager.setPendingSessionCreatedEmitUnsafe(null)
        }
    }

    private fun handleUnsubscribedEvent() {
        DebugLogger.d(TAG, "History watch unsubscribed")
        // Unsubscribed from session, but connection is still alive
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    private suspend fun emitPendingSessionCreatedEvent(eventSessionId: String) {
        sessionStateManager.sessionCreatedMutex.withLock {
            sessionStateManager.consumePendingSessionCreatedEmitUnsafe()?.let { sessionInfo ->
                if (sessionInfo.sessionId == eventSessionId) {
                    DebugLogger.d(TAG, "HistoryWatch fallback - emitting session created event: ${sessionInfo.sessionId}")
                    sessionStateManager.emitSessionCreatedEvent(sessionInfo)
                } else {
                    // Put it back if session ID doesn't match
                    sessionStateManager.setPendingSessionCreatedEmitUnsafe(sessionInfo)
                }
            }
        }
    }

    private suspend fun handleQueueOperations(event: HistoryWatchEvent.NewMessages) {
        val conversationIdFlow = getCurrentConversationIdFlow?.invoke() ?: return

        for (claudeMsg in event.messages) {
            if (claudeMsg.type == "queue-operation") {
                val operation = claudeMsg.operation
                val queueContent = claudeMsg.content ?: continue

                when (operation) {
                    "enqueue" -> handleEnqueueOperation(queueContent, conversationIdFlow)
                    "dequeue", "clear" -> handleDequeueOperation(conversationIdFlow)
                    "remove" -> handleRemoveOperation(claudeMsg, conversationIdFlow)
                }
            }
        }
    }

    private fun handleEnqueueOperation(queueContent: String, conversationIdFlow: StateFlow<String?>) {
        // Skip system notifications (bash-notification, etc.) - not user messages
        if (queueContent.trimStart().startsWith("<bash-notification>")) {
            DebugLogger.d(TAG, "Skipping bash-notification enqueue (not a user message)")
            return
        }

        val cliQueuedMessage = QueuedMessage(
            id = "cli_${messageStore.generateMessageId()}",
            content = queueContent,
            queuedAt = Clock.System.now().toEpochMilliseconds(),
            source = QueuedMessageSource.CLI
        )
        queueManager.updateCurrentQueue(conversationIdFlow.value) { queue -> queue + cliQueuedMessage }
        DebugLogger.d(TAG, "Queued message from CLI (id=${cliQueuedMessage.id}): ${queueContent.take(50)}...")
    }

    private fun handleDequeueOperation(conversationIdFlow: StateFlow<String?>) {
        val queue = queueManager.getCurrentQueue(conversationIdFlow.value)
        if (queue.isNotEmpty()) {
            DebugLogger.d(TAG, "Dequeued message from CLI")
            queueManager.updateCurrentQueue(conversationIdFlow.value) { q -> q.drop(1) }
        }
    }

    private suspend fun handleRemoveOperation(
        claudeMsg: com.claudecode.native.data.model.ClaudeMessage,
        conversationIdFlow: StateFlow<String?>
    ) {
        val removeTimestamp = claudeMsg.timestamp?.toEpochMilliseconds()
            ?: Clock.System.now().toEpochMilliseconds()

        val queue = queueManager.getCurrentQueue(conversationIdFlow.value)
        val messagesToRemove = queue.filter { it.queuedAt <= removeTimestamp }

        if (messagesToRemove.isNotEmpty()) {
            DebugLogger.d(TAG, "Remove operation - removing ${messagesToRemove.size} queued message(s)")
            queueManager.updateCurrentQueue(conversationIdFlow.value) { q ->
                q.filter { it.queuedAt > removeTimestamp }
            }
        }

        // Add removed messages as pending user messages
        messagesToRemove.forEach { msg ->
            addRemovedMessageAsPending(msg)
        }
    }

    private suspend fun addRemovedMessageAsPending(msg: QueuedMessage) {
        val normalizedContent = normalizeForComparison(msg.content)
        val normalizedHash = normalizedContent.hashCode()

        // Check if already confirmed
        val alreadyConfirmed = messageStore.messages.value.any { existingMsg ->
            existingMsg.role == MessageRole.USER &&
            !existingMsg.isPending &&
            normalizeForComparison(existingMsg.content) == normalizedContent
        }

        if (alreadyConfirmed) {
            DebugLogger.d(TAG, "Removed message already confirmed, skipping")
            return
        }

        val messageId = "pending_${messageStore.generateMessageId()}"
        val blocks = mutableListOf<ContentBlock>()
        if (msg.content.isNotBlank()) {
            blocks.add(ContentBlock.Text(msg.content))
        }
        msg.images.forEach { img ->
            blocks.add(ContentBlock.Image(
                ImageSource.Base64(
                    data = Base64.encode(img.data),
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

        messageStore.addPendingMessage(userMessage, normalizedHash)
        DebugLogger.d(TAG, "Added removed message as pending (id=$messageId)")
        messageStore.triggerScrollToBottom()
    }

    private fun logIncomingMessages(event: HistoryWatchEvent.NewMessages) {
        for ((idx, claudeMsg) in event.messages.withIndex()) {
            val msg = claudeMsg.message ?: continue
            val textPreview = onParseMessageContent?.invoke(msg.content, null)
                ?.filterIsInstance<ContentBlock.Text>()
                ?.joinToString(" ") { it.content }
                ?.take(50)
                ?.replace("\n", " ")
                ?: ""
            DebugLogger.d(TAG, "[$idx] role=${msg.role}, preview='$textPreview...'")
        }
    }

    private suspend fun detectAssistantActivity(event: HistoryWatchEvent.NewMessages) {
        val assistantMessage = event.messages.find { it.message?.role == "assistant" }
        if (assistantMessage != null) {
            val wasNotStreaming = !messageStore.getStreamingValue()
            if (wasNotStreaming) {
                DebugLogger.d(TAG, "Detected assistant activity from HistoryWatch, activating streaming state")
                messageStore.setStreaming(true)
                sessionStateManager.isStreamingFromHistoryWatch = true
                onStartProgressTracking?.invoke("Processing")
            }
        }
    }

    private fun updateTodosFromEvent(event: HistoryWatchEvent.NewMessages) {
        if (event.todos.isNotEmpty()) {
            val convId = sessionStateManager.currentConversationId ?: return
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
                    DebugLogger.d(TAG, "Updated todos (${event.todos.size} items, ${event.todos.count { it.isCompleted }} completed)")
                }
            }
        }
    }

    private suspend fun processMessagesWithTools(event: HistoryWatchEvent.NewMessages): List<ChatMessage> {
        // Extract tools from all new messages
        val newToolUses = mutableMapOf<String, ToolUseInfo>()
        val newToolResults = mutableMapOf<String, Pair<String, Boolean>>()

        for (claudeMsg in event.messages) {
            val msg = claudeMsg.message ?: continue
            toolTracker.extractToolsToMaps(msg.content, newToolUses, newToolResults)
        }

        DebugLogger.d(TAG, "Found ${newToolUses.size} tool_uses, ${newToolResults.size} tool_results")

        // Add new tools to session tracker and match results
        toolTracker.mergeAndMatchTools(newToolUses, newToolResults)

        // Get snapshot of matched tool uses
        val toolUsesSnapshot = toolTracker.getMatchedToolUses()

        // Convert to ChatMessages
        return event.messages.mapNotNull { claudeMsg ->
            convertToMessage(claudeMsg, toolUsesSnapshot)
        }
        // Deduplicate by UUID
        .groupBy { it.id }
        .map { (_, messages) -> messages.last() }
    }

    private fun convertToMessage(
        claudeMsg: com.claudecode.native.data.model.ClaudeMessage,
        toolUsesSnapshot: Map<String, ToolUseInfo>
    ): ChatMessage? {
        val msg = claudeMsg.message ?: return null
        val role = msg.role
        val blocks = onParseMessageContent?.invoke(msg.content, claudeMsg.uuid) ?: return null

        // Update tool blocks with results
        val blocksWithResults = toolTracker.updateBlocksWithToolResultsSync(blocks, toolUsesSnapshot)

        // Skip tool_result-only messages
        if (onHasOnlyToolResults?.invoke(msg.content) == true) {
            return null
        }

        // Skip meta messages
        if (claudeMsg.isMeta) {
            return null
        }

        // Skip messages with no content blocks
        if (blocksWithResults.isEmpty()) return null

        // Get text content for validation
        val textContent = blocksWithResults.filterIsInstance<ContentBlock.Text>()
            .joinToString("\n\n") { it.content }

        // Skip compaction/summary messages
        if (onIsCompactionMessage?.invoke(textContent) == true) return null

        val messageId = claudeMsg.uuid
            ?: "msg_${claudeMsg.timestamp?.toEpochMilliseconds() ?: Clock.System.now().toEpochMilliseconds()}"

        return ChatMessage(
            id = messageId,
            role = when (role) {
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                else -> return null
            },
            blocks = blocksWithResults,
            isStreaming = false,
            gitBranch = claudeMsg.gitBranch,
            agentId = claudeMsg.agentId,
            isSidechain = claudeMsg.isSidechain
        )
    }

    private suspend fun updateExistingToolResults() {
        val toolUsesSnap = toolTracker.getMatchedToolUses()
        messageStore.updateToolResults { msg ->
            val hasToolsWithoutResults = msg.blocks.any { block ->
                block is ContentBlock.Tool && block.info.result == null
            }
            if (hasToolsWithoutResults) {
                val updatedBlocks = toolTracker.updateBlocksWithToolResultsSync(msg.blocks, toolUsesSnap)
                if (updatedBlocks != msg.blocks) {
                    DebugLogger.d(TAG, "Updated message ${msg.id} with tool results")
                    msg.copy(blocks = updatedBlocks)
                } else null
            } else null
        }
    }

    private suspend fun mergeNewMessages(newChatMessages: List<ChatMessage>) {
        val conversationIdFlow = getCurrentConversationIdFlow?.invoke() ?: return

        // Pre-process: remove pending message tracking for user messages
        for (newMsg in newChatMessages) {
            if (newMsg.role == MessageRole.USER) {
                val normalizedContentHash = normalizeForComparison(newMsg.content).hashCode()
                messageStore.removePendingMessage(normalizedContentHash)

                // Also remove matching queued messages
                val normalizedContent = normalizeForComparison(newMsg.content)
                queueManager.updateCurrentQueue(conversationIdFlow.value) { queue ->
                    val matchingIndex = queue.indexOfFirst {
                        normalizeForComparison(it.content) == normalizedContent
                    }
                    if (matchingIndex >= 0) {
                        DebugLogger.d(TAG, "Removed confirmed queued message from queue")
                        queue.filterIndexed { index, _ -> index != matchingIndex }
                    } else {
                        queue
                    }
                }
            }
        }

        // Use MessageStore's mergeMessages for thread-safe update
        messageStore.mergeMessages(newChatMessages) { currentList, newMessages ->
            // Build ID -> index map for O(1) lookup
            val idToIndex = currentList.withIndex()
                .associate { (index, msg) -> msg.id to index }
                .toMutableMap()
            var updated = false

            for (newMsg in newMessages) {
                // Check if this is a pending user message
                val normalizedContentHash = normalizeForComparison(newMsg.content).hashCode()
                val pendingMsgId = if (newMsg.role == MessageRole.USER) {
                    idToIndex.entries.find { (_, idx) ->
                        val msg = currentList[idx]
                        msg.isPending && msg.role == MessageRole.USER &&
                            normalizeForComparison(msg.content).hashCode() == normalizedContentHash
                    }?.let { (id, _) -> id }
                } else null

                if (pendingMsgId != null) {
                    // This user message was confirmed - update isPending to false
                    val pendingIndex = idToIndex[pendingMsgId]
                    if (pendingIndex != null) {
                        currentList[pendingIndex] = currentList[pendingIndex].copy(isPending = false)
                        updated = true
                    }
                    continue
                }

                // O(1) ID lookup using index map
                val existingIndex = idToIndex[newMsg.id]

                if (existingIndex != null) {
                    // Message exists - update with latest blocks
                    val existing = currentList[existingIndex]
                    currentList[existingIndex] = existing.copy(
                        blocks = newMsg.blocks,
                        isStreaming = false
                    )
                    updated = true
                } else {
                    // New message - add it and update index
                    idToIndex[newMsg.id] = currentList.size
                    currentList.add(newMsg)
                    updated = true
                }
            }

            if (updated) {
                DebugLogger.d(TAG, "HistoryWatch update, total=${currentList.size}")
            }
        }
    }

    private suspend fun checkSessionStateForCompletion(sessionState: SessionState?) {
        if (sessionState == SessionState.IDLE) {
            if (messageStore.getStreamingValue()) {
                DebugLogger.d(TAG, "HistoryWatch detected IDLE state, finalizing streaming")
                messageStore.setStreaming(false)
                sessionStateManager.isStreamingFromHistoryWatch = false
            }
            onStopProgressTracking?.invoke()
            // Clear queued messages that were sent to CLI
            queueManager.clearSentQueueMessages(sessionStateManager.currentConversationIdFlow.value)
        }
    }

    /**
     * Finalizes streaming state. Called when streaming completes.
     */
    suspend fun finalizeStreamingMessage(onQueueCleanup: suspend () -> Unit) {
        // Reset streaming state from HistoryWatch tracking
        sessionStateManager.isStreamingFromHistoryWatch = false

        // Stop progress tracking
        onStopProgressTracking?.invoke()

        // Delegate finalization to MessageStore
        messageStore.onStreamingComplete = onQueueCleanup
        messageStore.finalizeStreaming()
    }
}
