package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.model.MessageRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi

/**
 * MessageStore manages the state of chat messages and related state for ChatViewModel.
 *
 * This class centralizes message state management with proper synchronization to prevent
 * race conditions when multiple coroutines access or modify message state concurrently.
 *
 * ## Thread Safety
 *
 * MessageStore uses a strict lock ordering to prevent deadlocks:
 * 1. `streamingMutex` (Lock #1) - Protects streaming state
 * 2. `mutex` (Lock #2) - Protects message list
 * 3. `mapsMutex` (Lock #3) - Protects pending user messages map
 *
 * When acquiring multiple locks, always acquire them in the order above.
 *
 * ## Usage
 *
 * ```kotlin
 * val messageStore = MessageStore()
 *
 * // Add a message
 * messageStore.addMessage(chatMessage)
 *
 * // Update streaming state
 * messageStore.setStreaming(true)
 *
 * // Observe messages
 * messageStore.messages.collect { messages -> ... }
 * ```
 */
class MessageStore {

    // ============================================================================
    // State Variables
    // ============================================================================

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    /**
     * Flow of chat messages in the conversation.
     * Observe this to get real-time updates when messages change.
     */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    /**
     * True when the assistant is actively streaming a response.
     */
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    /**
     * True when there are more messages to load (pagination).
     */
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    /**
     * True when loading more messages (pagination in progress).
     */
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /**
     * Current offset for pagination (number of messages already loaded).
     * Not exposed as StateFlow since it's only used internally.
     */
    var currentMessagesOffset: Int = 0
        private set

    private val _scrollToBottomSignal = MutableStateFlow(0)
    /**
     * Signal for UI to scroll to bottom. Incremented when:
     * - Streaming completes (finalizeStreamingMessage)
     * - User sends a message
     * UI should observe and scroll when value changes.
     */
    val scrollToBottomSignal: StateFlow<Int> = _scrollToBottomSignal.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /**
     * Current error message, if any.
     */
    val error: StateFlow<String?> = _error.asStateFlow()

    // ============================================================================
    // Synchronization Primitives
    // ============================================================================

    /**
     * LOCK ORDERING (always acquire in this order to prevent deadlocks):
     * 1. streamingMutex - Protects streaming state (_isStreaming)
     * 2. mutex - Protects message list (_messages)
     * 3. mapsMutex - Protects pendingUserMessages map
     *
     * Note: These locks are internal implementation details. External code should
     * use the public API methods which handle locking internally.
     */

    /**
     * Lock #1: Protects streaming state changes.
     * Must be acquired before mutex and mapsMutex when multiple locks are needed.
     */
    private val streamingMutex = Mutex()

    /**
     * Lock #2: Protects _messages updates.
     * Must be acquired after streamingMutex when both are needed.
     */
    private val mutex = Mutex()

    /**
     * Lock #3: Protects pendingUserMessages map.
     * Must be acquired after mutex when both are needed.
     */
    private val mapsMutex = Mutex()

    /**
     * Track pending user messages to properly handle duplicates from history watch.
     * Key: content hash, Value: message ID
     * Private - access through addPendingMessage/removePendingMessage/hasPendingMessage.
     */
    private val pendingUserMessages = mutableMapOf<Int, String>()

    // ============================================================================
    // Callbacks for ChatViewModel Context
    // ============================================================================

    /**
     * Called when streaming is finalized and pending messages need to be cleaned up.
     * ChatViewModel should set this to perform cleanup after streaming completes.
     */
    var onStreamingComplete: (suspend () -> Unit)? = null

    // ============================================================================
    // Core Operations
    // ============================================================================

    /**
     * Clears all message-related state for switching conversations or starting fresh.
     * This includes messages, pagination state, and pending user message tracking.
     *
     * Note: Acquires locks in documented order (mutex → mapsMutex), though each lock
     * is released before acquiring the next, so deadlock is not a concern here.
     */
    suspend fun clearState() {
        mutex.withLock {
            _messages.value = emptyList()
        }
        mapsMutex.withLock {
            pendingUserMessages.clear()
        }
        currentMessagesOffset = 0
        _hasMoreMessages.value = false
        _isLoadingMore.value = false
    }

    /**
     * Adds a new message to the message list.
     * Thread-safe operation protected by mutex.
     *
     * @param message The chat message to add
     */
    suspend fun addMessage(message: ChatMessage) {
        mutex.withLock {
            _messages.value = _messages.value + message
        }
    }

    /**
     * Updates an existing message by ID using the provided updater function.
     * If no message with the given ID exists, this is a no-op.
     *
     * @param id The message ID to update
     * @param updater Function that takes the current message and returns the updated message
     * @return True if a message was updated, false if no matching message was found
     */
    suspend fun updateMessage(id: String, updater: (ChatMessage) -> ChatMessage): Boolean {
        return mutex.withLock {
            val currentMessages = _messages.value.toMutableList()
            val index = currentMessages.indexOfFirst { it.id == id }
            if (index >= 0) {
                currentMessages[index] = updater(currentMessages[index])
                _messages.value = currentMessages
                true
            } else {
                false
            }
        }
    }

    /**
     * Replaces all messages with the provided list.
     * Thread-safe operation protected by mutex.
     *
     * @param messages The new list of messages
     */
    suspend fun setMessages(messages: List<ChatMessage>) {
        mutex.withLock {
            _messages.value = messages
        }
    }

    /**
     * Prepends older messages to the existing list (for pagination).
     * Automatically filters out duplicates based on message ID.
     *
     * @param olderMessages The older messages to prepend
     * @return The number of unique messages that were actually added
     */
    suspend fun prependMessages(olderMessages: List<ChatMessage>): Int {
        return mutex.withLock {
            val currentMessages = _messages.value
            val existingIds = currentMessages.map { it.id }.toSet()
            val uniqueOlderMessages = olderMessages.filter { it.id !in existingIds }
            _messages.value = uniqueOlderMessages + currentMessages
            uniqueOlderMessages.size
        }
    }

    /**
     * Sets the streaming state.
     * Thread-safe operation protected by streamingMutex.
     *
     * @param value True if streaming is active, false otherwise
     */
    suspend fun setStreaming(value: Boolean) {
        streamingMutex.withLock {
            _isStreaming.value = value
        }
    }

    /**
     * Atomically sets streaming to true if not already streaming.
     * Used to handle reconnection scenarios where we receive stream chunks
     * while streaming state is inactive.
     *
     * @return True if streaming state was changed (was not active), false if already streaming
     */
    suspend fun setStreamingIfNotActive(): Boolean {
        return streamingMutex.withLock {
            if (!_isStreaming.value) {
                _isStreaming.value = true
                true
            } else {
                false
            }
        }
    }

    /**
     * Gets the current streaming state.
     * For quick checks where synchronization is not critical.
     *
     * @return Current streaming state
     */
    fun getStreamingValue(): Boolean = _isStreaming.value

    /**
     * Finalizes a streaming message by updating streaming state and cleaning up.
     * This should be called when streaming completes (either normally or via stop).
     *
     * Performs the following:
     * 1. Resets streaming state to false
     * 2. Clears remaining pending user messages as fallback
     * 3. Triggers scroll to bottom signal
     * 4. Invokes onStreamingComplete callback if set
     */
    suspend fun finalizeStreaming() {
        streamingMutex.withLock {
            _isStreaming.value = false
        }

        // Clear any remaining pending user messages as fallback
        // When streaming completes, we assume all user messages have been processed
        // Lock ordering: mutex (#2) -> mapsMutex (#3) for thread-safe access
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
        triggerScrollToBottom()

        // Invoke callback for additional cleanup in ChatViewModel
        onStreamingComplete?.invoke()
    }

    /**
     * Triggers the scroll to bottom signal.
     * UI should observe scrollToBottomSignal and scroll when it changes.
     */
    fun triggerScrollToBottom() {
        _scrollToBottomSignal.update { it + 1 }
    }

    /**
     * Sets the error message.
     *
     * @param error The error message, or null to clear the error
     */
    fun setError(error: String?) {
        _error.value = error
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _error.value = null
    }

    // ============================================================================
    // Pending Message Tracking
    // ============================================================================

    /**
     * Registers a pending user message for duplicate tracking.
     * Called when a user message is sent locally before server confirmation.
     *
     * @param hash The normalized content hash of the message
     * @param messageId The message ID
     */
    suspend fun registerPendingMessage(hash: Int, messageId: String) {
        mapsMutex.withLock {
            pendingUserMessages[hash] = messageId
        }
    }

    /**
     * Removes a pending message from tracking and returns its ID if found.
     *
     * @param hash The normalized content hash of the message
     * @return The message ID if found, null otherwise
     */
    suspend fun removePendingMessage(hash: Int): String? {
        return mapsMutex.withLock {
            pendingUserMessages.remove(hash)
        }
    }

    /**
     * Gets the pending message ID for a given content hash without removing it.
     *
     * @param hash The normalized content hash of the message
     * @return The message ID if found, null otherwise
     */
    suspend fun getPendingMessageId(hash: Int): String? {
        return mapsMutex.withLock {
            pendingUserMessages[hash]
        }
    }

    // ============================================================================
    // Pagination State
    // ============================================================================

    /**
     * Updates pagination state after loading messages (increments offset).
     *
     * @param loadedCount Number of messages loaded in this batch
     * @param hasMore Whether there are more messages available
     */
    fun updatePaginationState(loadedCount: Int, hasMore: Boolean) {
        currentMessagesOffset += loadedCount
        _hasMoreMessages.value = hasMore
    }

    /**
     * Sets pagination state (initial load, sets offset directly).
     *
     * @param offset The new offset value
     * @param hasMore Whether there are more messages available
     */
    fun setPaginationState(offset: Int, hasMore: Boolean) {
        currentMessagesOffset = offset
        _hasMoreMessages.value = hasMore
    }

    /**
     * Sets the loading more state for pagination.
     *
     * @param value True if currently loading more messages
     */
    fun setLoadingMore(value: Boolean) {
        _isLoadingMore.value = value
    }

    /**
     * Sets the has more messages state for pagination.
     *
     * @param value True if there are more messages to load
     */
    fun setHasMoreMessages(value: Boolean) {
        _hasMoreMessages.value = value
    }

    /**
     * Resets pagination state.
     */
    fun resetPagination() {
        currentMessagesOffset = 0
        _hasMoreMessages.value = false
        _isLoadingMore.value = false
    }

    // ============================================================================
    // Utility Methods
    // ============================================================================

    /**
     * Generates a new unique message ID using UUID.
     *
     * @return A new UUID string
     */
    @OptIn(ExperimentalUuidApi::class)
    fun generateMessageId(): String {
        return kotlin.uuid.Uuid.random().toString()
    }

    /**
     * Gets the current message count.
     * For quick checks without synchronization.
     *
     * @return The number of messages currently stored
     */
    fun getMessageCount(): Int = _messages.value.size

    /**
     * Gets the current messages list.
     * For quick access without synchronization.
     *
     * @return The current list of messages
     */
    fun getMessagesValue(): List<ChatMessage> = _messages.value

    /**
     * Adds a message with pending status and registers it for tracking.
     * This is a convenience method that combines addMessage and registerPendingMessage.
     *
     * @param message The message to add (should have isPending = true)
     * @param contentHash The normalized content hash for tracking
     */
    suspend fun addPendingMessage(message: ChatMessage, contentHash: Int) {
        // Lock ordering: mutex (#2) -> mapsMutex (#3)
        mutex.withLock {
            mapsMutex.withLock {
                pendingUserMessages[contentHash] = message.id
            }
            _messages.value = _messages.value + message
        }
    }

    /**
     * Updates messages with a transformation function and optional deduplication.
     * Useful for merging incoming messages with existing ones.
     *
     * @param newMessages New messages to process
     * @param merger Function that takes current messages and new messages, returns merged result
     */
    suspend fun mergeMessages(
        newMessages: List<ChatMessage>,
        merger: (current: MutableList<ChatMessage>, new: List<ChatMessage>) -> Unit
    ) {
        mutex.withLock {
            val currentMessages = _messages.value.toMutableList()
            merger(currentMessages, newMessages)
            _messages.value = currentMessages.toList()
        }
    }

    /**
     * Updates tool blocks with results for messages that have incomplete tool info.
     * Used when tool results arrive after the initial tool use message.
     *
     * @param toolUpdater Function that updates tool blocks with results
     */
    suspend fun updateToolResults(
        toolUpdater: (message: ChatMessage) -> ChatMessage?
    ) {
        mutex.withLock {
            val currentMessages = _messages.value.toMutableList()
            var messagesUpdated = false

            for (i in currentMessages.indices) {
                val updated = toolUpdater(currentMessages[i])
                if (updated != null) {
                    currentMessages[i] = updated
                    messagesUpdated = true
                }
            }

            if (messagesUpdated) {
                _messages.value = currentMessages.toList()
            }
        }
    }
}
