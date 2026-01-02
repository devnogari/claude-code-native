package com.claudecode.native.data.api

import com.claudecode.native.data.model.AddToQueueRequest
import com.claudecode.native.data.model.QueueAddResponse
import com.claudecode.native.data.model.QueueListResponse
import com.claudecode.native.data.model.QueueSuccessResponse

/**
 * API client for queue operations.
 * Manages the server-side message queue for conversations.
 */
class QueueApi(private val client: ApiClient) {

    /**
     * Retrieves all queued messages for a conversation.
     *
     * @param conversationId The conversation ID
     * @return List of queued messages
     */
    suspend fun getQueue(conversationId: String): QueueListResponse {
        return client.get("/conversations/$conversationId/queue")
    }

    /**
     * Adds a message to the queue.
     *
     * @param conversationId The conversation ID
     * @param content The message content
     * @return The created queued message
     */
    suspend fun addToQueue(conversationId: String, content: String): QueueAddResponse {
        return client.post("/conversations/$conversationId/queue", AddToQueueRequest(content))
    }

    /**
     * Removes a specific message from the queue.
     *
     * @param conversationId The conversation ID
     * @param messageId The queued message ID to remove
     */
    suspend fun removeFromQueue(conversationId: String, messageId: String) {
        client.delete("/conversations/$conversationId/queue/$messageId")
    }

    /**
     * Clears all messages from the queue for a conversation.
     *
     * @param conversationId The conversation ID
     */
    suspend fun clearQueue(conversationId: String) {
        client.delete("/conversations/$conversationId/queue")
    }
}
