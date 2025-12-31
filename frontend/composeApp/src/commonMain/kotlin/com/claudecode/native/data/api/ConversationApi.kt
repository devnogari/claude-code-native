package com.claudecode.native.data.api

import com.claudecode.native.data.model.Conversation
import com.claudecode.native.data.model.CreateConversationRequest
import com.claudecode.native.data.model.UpdateConversationRequest
import io.ktor.http.encodeURLParameter

/**
 * API client for conversation operations.
 * Handles CRUD operations for conversations within projects.
 */
class ConversationApi(private val client: ApiClient) {

    /**
     * Retrieves all conversations for a specific project.
     *
     * @param projectId The project ID to get conversations for
     * @return List of conversations
     */
    suspend fun getConversations(projectId: String): List<Conversation> {
        return client.get("/projects/$projectId/conversations")
    }

    /**
     * Retrieves a specific conversation by ID.
     *
     * @param id The conversation ID
     * @return The conversation details
     */
    suspend fun getConversation(id: String): Conversation {
        return client.get("/conversations/$id")
    }

    /**
     * Creates a new conversation within a project.
     *
     * @param projectId The project ID to create the conversation in
     * @param title Optional title for the conversation
     * @return The created conversation
     */
    suspend fun createConversation(projectId: String, title: String? = null): Conversation {
        return client.post("/projects/$projectId/conversations", CreateConversationRequest(projectId, title))
    }

    /**
     * Updates an existing conversation.
     *
     * @param id The conversation ID
     * @param title The new conversation title (optional)
     * @return The updated conversation
     */
    suspend fun updateConversation(id: String, title: String?): Conversation {
        return client.put("/conversations/$id", UpdateConversationRequest(title))
    }

    /**
     * Deletes a conversation.
     *
     * @param id The conversation ID to delete
     */
    suspend fun deleteConversation(id: String) {
        client.delete("/conversations/$id")
    }

    /**
     * Deletes the Claude CLI session for a conversation.
     * This removes session files from ~/.claude/ allowing a fresh start.
     *
     * @param id The conversation ID
     */
    suspend fun deleteSession(id: String) {
        client.delete("/conversations/$id/session")
    }

    /**
     * Deletes the Claude CLI session directly by session ID and project path.
     * Use this for Claude History sessions that may not be in the database.
     *
     * @param sessionId The session ID (same as conversation ID for local sessions)
     * @param projectPath The project path (used to find session files)
     */
    suspend fun deleteSessionDirect(sessionId: String, projectPath: String) {
        val encodedPath = projectPath.encodeURLParameter()
        client.delete("/sessions/$sessionId?path=$encodedPath")
    }

    /**
     * Toggles the favorite status of a conversation.
     *
     * @param id The conversation ID
     * @return The updated conversation with toggled favorite status
     */
    suspend fun toggleFavorite(id: String): Conversation {
        return client.post("/conversations/$id/favorite", Unit)
    }
}
