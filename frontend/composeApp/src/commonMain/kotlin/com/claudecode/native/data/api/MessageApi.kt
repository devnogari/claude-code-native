package com.claudecode.native.data.api

import com.claudecode.native.data.model.Message

/**
 * API client for message operations.
 */
class MessageApi(private val client: ApiClient) {

    /**
     * Retrieves all messages for a conversation.
     *
     * @param conversationId The conversation ID
     * @return List of messages ordered by sequence
     */
    suspend fun getMessages(conversationId: String): List<Message> {
        return client.get("/conversations/$conversationId/messages")
    }
}
