package com.claudecode.native.data.api

import com.claudecode.native.data.model.PaginatedMessagesResponse

/**
 * API client for message operations.
 */
class MessageApi(private val client: ApiClient) {

    companion object {
        const val DEFAULT_LIMIT = 50
    }

    /**
     * Retrieves messages for a conversation with pagination.
     * Returns most recent messages first (offset 0 = most recent).
     *
     * @param conversationId The conversation ID
     * @param limit Number of messages to fetch (default 50, max 200)
     * @param offset Offset from most recent message (default 0)
     * @return Paginated messages response
     */
    suspend fun getMessages(
        conversationId: String,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0
    ): PaginatedMessagesResponse {
        return client.get("/conversations/$conversationId/messages?limit=$limit&offset=$offset")
    }
}
