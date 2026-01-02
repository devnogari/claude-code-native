package com.claudecode.native.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages message state for a conversation.
 *
 * Provides thread-safe operations for:
 * - Message list management (add, prepend, update, clear)
 * - Pagination state (hasMoreMessages, isLoadingMore, offset)
 * - Message lookup by ID
 *
 * This class is extracted from ChatViewModel to separate message state
 * management from the view model's other responsibilities.
 *
 * Thread Safety:
 * All message list mutations are protected by a Mutex to ensure
 * thread-safe access from multiple coroutines.
 */
class MessageManager {

    private val mutex = Mutex()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    /**
     * Flow of chat messages in the conversation.
     * Messages are ordered chronologically (oldest first).
     */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    /**
     * True when there are more (older) messages to load via pagination.
     */
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    /**
     * True when pagination is in progress (loading older messages).
     */
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /**
     * Current offset for pagination (number of messages already loaded).
     * Access is synchronized via mutex in pagination operations.
     */
    private var currentOffset: Int = 0

    // ===== Message List Operations =====

    /**
     * Adds a single message to the end of the message list.
     *
     * @param message The message to add
     */
    suspend fun addMessage(message: ChatMessage) {
        mutex.withLock {
            _messages.value = _messages.value + message
        }
    }

    /**
     * Adds multiple messages to the end of the message list.
     *
     * @param messages The messages to add (appended in order)
     */
    suspend fun addMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        mutex.withLock {
            _messages.value = _messages.value + messages
        }
    }

    /**
     * Prepends messages to the beginning of the list (for pagination).
     * Automatically deduplicates by message ID.
     *
     * @param messages The older messages to prepend
     */
    suspend fun prependMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        mutex.withLock {
            val currentMessages = _messages.value
            val existingIds = currentMessages.map { it.id }.toSet()
            val uniqueNewMessages = messages.filter { it.id !in existingIds }
            _messages.value = uniqueNewMessages + currentMessages
        }
    }

    /**
     * Replaces all messages with a new list.
     *
     * @param messages The new message list
     */
    suspend fun setMessages(messages: List<ChatMessage>) {
        mutex.withLock {
            _messages.value = messages
        }
    }

    /**
     * Clears all messages and resets pagination state.
     */
    suspend fun clearMessages() {
        mutex.withLock {
            _messages.value = emptyList()
        }
        currentOffset = 0
        _hasMoreMessages.value = false
        _isLoadingMore.value = false
    }

    /**
     * Updates a message by ID using a transform function.
     * If the message is not found, no changes are made.
     *
     * @param messageId The ID of the message to update
     * @param transform Function to transform the message
     */
    suspend fun updateMessage(messageId: String, transform: (ChatMessage) -> ChatMessage) {
        mutex.withLock {
            val currentMessages = _messages.value.toMutableList()
            val index = currentMessages.indexOfFirst { it.id == messageId }
            if (index != -1) {
                currentMessages[index] = transform(currentMessages[index])
                _messages.value = currentMessages.toList()
            }
        }
    }

    /**
     * Finds a message by ID.
     *
     * @param messageId The ID to search for
     * @return The message if found, null otherwise
     */
    fun getMessageById(messageId: String): ChatMessage? {
        return _messages.value.find { it.id == messageId }
    }

    // ===== Pagination State Operations =====

    /**
     * Sets whether there are more messages to load.
     *
     * @param hasMore True if more messages are available
     */
    fun setHasMoreMessages(hasMore: Boolean) {
        _hasMoreMessages.value = hasMore
    }

    /**
     * Sets whether pagination is currently in progress.
     *
     * @param loading True if loading more messages
     */
    fun setLoadingMore(loading: Boolean) {
        _isLoadingMore.value = loading
    }

    /**
     * Returns the current pagination offset.
     */
    fun getCurrentOffset(): Int = currentOffset

    /**
     * Increments the pagination offset by the given amount.
     *
     * @param amount The number of messages loaded
     */
    fun incrementOffset(amount: Int) {
        currentOffset += amount
    }

    /**
     * Resets the pagination offset to zero.
     */
    fun resetOffset() {
        currentOffset = 0
    }
}
