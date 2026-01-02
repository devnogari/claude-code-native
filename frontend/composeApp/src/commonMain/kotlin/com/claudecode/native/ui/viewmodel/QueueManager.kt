package com.claudecode.native.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages per-conversation message queues with thread-safe operations.
 *
 * This class provides isolated queue management for each conversation,
 * ensuring that queue operations in one conversation don't affect others.
 * All operations are thread-safe using a Mutex.
 *
 * @param maxQueueSize Maximum number of messages allowed per queue (default: 10)
 */
class QueueManager(
    private val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE
) {
    companion object {
        const val DEFAULT_MAX_QUEUE_SIZE = 10
    }

    /**
     * Internal map storing queues per conversation.
     * Key: conversationId, Value: StateFlow of queued messages
     */
    private val _queuesMap = MutableStateFlow<Map<String, List<QueuedMessage>>>(emptyMap())

    /** Mutex for thread-safe operations on the queue map */
    private val mutex = Mutex()

    /**
     * Gets a StateFlow of the queue for a specific conversation.
     * Returns an empty list if no queue exists for the conversation.
     *
     * @param conversationId The conversation ID to get the queue for
     * @return StateFlow emitting the current queue state
     */
    fun getQueue(conversationId: String): StateFlow<List<QueuedMessage>> {
        // Create a derived StateFlow that always returns the queue for this conversation
        return object : StateFlow<List<QueuedMessage>> {
            override val replayCache: List<List<QueuedMessage>>
                get() = listOf(value)

            override val value: List<QueuedMessage>
                get() = _queuesMap.value[conversationId] ?: emptyList()

            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<List<QueuedMessage>>): Nothing {
                _queuesMap.collect { map ->
                    collector.emit(map[conversationId] ?: emptyList())
                }
            }
        }
    }

    /**
     * Adds a message to the queue for a specific conversation.
     * If the queue is at max capacity, the message will still be added
     * but excess messages will be trimmed from the beginning.
     *
     * @param conversationId The conversation ID to add the message to
     * @param message The message to add
     */
    suspend fun addToQueue(conversationId: String, message: QueuedMessage) {
        mutex.withLock {
            _queuesMap.update { map ->
                val currentQueue = map[conversationId] ?: emptyList()
                val newQueue = (currentQueue + message).takeLast(maxQueueSize)
                map + (conversationId to newQueue)
            }
        }
    }

    /**
     * Removes a message from the queue by ID.
     *
     * @param conversationId The conversation ID
     * @param messageId The ID of the message to remove
     */
    suspend fun removeFromQueue(conversationId: String, messageId: String) {
        mutex.withLock {
            _queuesMap.update { map ->
                val currentQueue = map[conversationId] ?: return@update map
                map + (conversationId to currentQueue.filter { it.id != messageId })
            }
        }
    }

    /**
     * Removes all messages with queuedAt timestamp before the specified time.
     * Messages at exactly the timestamp are kept.
     *
     * @param conversationId The conversation ID
     * @param timestamp The cutoff timestamp (epoch milliseconds)
     */
    suspend fun removeMessagesBefore(conversationId: String, timestamp: Long) {
        mutex.withLock {
            _queuesMap.update { map ->
                val currentQueue = map[conversationId] ?: return@update map
                map + (conversationId to currentQueue.filter { it.queuedAt >= timestamp })
            }
        }
    }

    /**
     * Clears all messages from a conversation's queue.
     *
     * @param conversationId The conversation ID to clear
     */
    suspend fun clearQueue(conversationId: String) {
        mutex.withLock {
            _queuesMap.update { map ->
                map + (conversationId to emptyList())
            }
        }
    }

    /**
     * Replaces the entire queue for a conversation with the given list.
     * The list will be trimmed to maxQueueSize if it exceeds the limit.
     *
     * @param conversationId The conversation ID
     * @param queue The new queue contents
     */
    suspend fun updateQueue(conversationId: String, queue: List<QueuedMessage>) {
        mutex.withLock {
            _queuesMap.update { map ->
                map + (conversationId to queue.takeLast(maxQueueSize))
            }
        }
    }

    /**
     * Applies a transformation function to the queue for a conversation.
     * Useful for complex queue operations that need atomic updates.
     *
     * @param conversationId The conversation ID
     * @param transform Function that transforms the current queue to a new queue
     */
    suspend fun transformQueue(conversationId: String, transform: (List<QueuedMessage>) -> List<QueuedMessage>) {
        mutex.withLock {
            _queuesMap.update { map ->
                val currentQueue = map[conversationId] ?: emptyList()
                val newQueue = transform(currentQueue).takeLast(maxQueueSize)
                map + (conversationId to newQueue)
            }
        }
    }

    /**
     * Gets the current size of the queue for a conversation.
     *
     * @param conversationId The conversation ID
     * @return The number of messages in the queue
     */
    fun getQueueSize(conversationId: String): Int {
        return _queuesMap.value[conversationId]?.size ?: 0
    }

    /**
     * Checks if any messages in the queue originated from LOCAL source.
     *
     * @param conversationId The conversation ID
     * @return true if there are any LOCAL source messages
     */
    fun hasLocalMessages(conversationId: String): Boolean {
        return _queuesMap.value[conversationId]?.any {
            it.source == QueuedMessageSource.LOCAL
        } ?: false
    }

    /**
     * Gets all messages from the queue that have LOCAL source.
     *
     * @param conversationId The conversation ID
     * @return List of locally-queued messages
     */
    fun getLocalMessages(conversationId: String): List<QueuedMessage> {
        return _queuesMap.value[conversationId]?.filter {
            it.source == QueuedMessageSource.LOCAL
        } ?: emptyList()
    }

    /**
     * Checks if the queue for a conversation is at maximum capacity.
     *
     * @param conversationId The conversation ID
     * @return true if the queue is full
     */
    fun isQueueFull(conversationId: String): Boolean {
        return getQueueSize(conversationId) >= maxQueueSize
    }

    /**
     * Gets the current queue contents synchronously.
     *
     * @param conversationId The conversation ID
     * @return The current queue as a list
     */
    fun getCurrentQueue(conversationId: String): List<QueuedMessage> {
        return _queuesMap.value[conversationId] ?: emptyList()
    }
}
