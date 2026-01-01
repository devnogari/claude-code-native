package com.claudecode.native.data.api

import com.claudecode.native.data.model.ClaudeProject
import com.claudecode.native.data.model.PaginatedClaudeMessagesResponse
import com.claudecode.native.data.model.SessionStateResponse
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request to toggle session favorite status.
 */
@Serializable
data class ToggleSessionFavoriteRequest(
    @SerialName("project_path") val projectPath: String
)

/**
 * Response from toggling session favorite status.
 */
@Serializable
data class ToggleSessionFavoriteResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("is_favorite") val isFavorite: Boolean
)

/**
 * API client for Claude Code history operations.
 * Reads local Claude Code conversations from ~/.claude/projects/
 */
class ClaudeHistoryApi(private val client: ApiClient) {

    /**
     * Retrieves all Claude Code projects from the local filesystem.
     *
     * @return List of Claude Code projects with their sessions
     */
    suspend fun getProjects(): List<ClaudeProject> {
        return client.get("/claude-history/projects")
    }

    /**
     * Retrieves a specific Claude Code project by its encoded path.
     *
     * @param encodedPath The encoded project path (e.g., "Users-probe-git-myproject")
     * @return The project details with sessions
     */
    suspend fun getProject(encodedPath: String): ClaudeProject {
        return client.get("/claude-history/projects/$encodedPath")
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val DEFAULT_MAX_CONTENT_LENGTH = 500
    }

    /**
     * Retrieves messages from a specific session with pagination.
     * Messages are returned with most recent first (offset 0 = most recent).
     *
     * @param encodedPath The encoded project path
     * @param sessionId The session ID
     * @param limit Number of messages to fetch (default 50, max 200)
     * @param offset Offset from most recent message (default 0)
     * @param summary If true, content is truncated (default true for efficiency)
     * @param maxContentLength Max length for truncated content (default 500)
     * @return Paginated response with messages
     */
    suspend fun getSessionMessages(
        encodedPath: String,
        sessionId: String,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        summary: Boolean = true,
        maxContentLength: Int = DEFAULT_MAX_CONTENT_LENGTH
    ): PaginatedClaudeMessagesResponse {
        val params = buildString {
            append("?limit=$limit&offset=$offset")
            if (!summary) {
                append("&summary=false")
            } else if (maxContentLength != DEFAULT_MAX_CONTENT_LENGTH) {
                append("&maxContentLength=$maxContentLength")
            }
        }
        return client.get("/claude-history/projects/$encodedPath/sessions/$sessionId$params")
    }

    /**
     * Toggles the favorite status of a session.
     *
     * @param sessionId The session ID
     * @param projectPath The project path
     * @return The new favorite status
     */
    suspend fun toggleSessionFavorite(sessionId: String, projectPath: String): ToggleSessionFavoriteResponse {
        return client.post(
            "/claude-history/sessions/$sessionId/favorite",
            ToggleSessionFavoriteRequest(projectPath)
        )
    }

    /**
     * Refreshes the cache and reloads all projects.
     * Forces the backend to rescan ~/.claude/projects/ for new sessions.
     */
    suspend fun refresh() {
        client.postEmpty<Map<String, String>>("/claude-history/refresh")
    }

    /**
     * Deletes a Claude CLI session's files.
     *
     * @param sessionId The session ID to delete
     * @param projectPath The project path where the session resides
     * @param sourceEncodedPath Optional encoded path where session file actually resides (for inherited sessions)
     */
    suspend fun deleteSession(sessionId: String, projectPath: String, sourceEncodedPath: String? = null) {
        // Use Ktor's built-in URL encoding for consistency
        val encodedPath = projectPath.encodeURLParameter()
        val queryParams = buildString {
            append("path=$encodedPath")
            if (sourceEncodedPath != null) {
                append("&source_encoded_path=${sourceEncodedPath.encodeURLParameter()}")
            }
        }
        client.delete("/sessions/$sessionId?$queryParams")
    }

    /**
     * Retrieves current session state including todos and streaming status.
     * This is a REST fallback for when WebSocket messages are missed.
     *
     * @param encodedPath The encoded project path
     * @param sessionId The session ID
     * @return Session state with todos and streaming status
     */
    suspend fun getSessionState(encodedPath: String, sessionId: String): SessionStateResponse {
        return client.get("/claude-history/projects/$encodedPath/sessions/$sessionId/state")
    }
}
