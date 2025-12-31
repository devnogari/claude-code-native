package com.claudecode.native.data.api

import com.claudecode.native.data.model.ClaudeMessage
import com.claudecode.native.data.model.ClaudeProject

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

    /**
     * Retrieves all messages from a specific session.
     *
     * @param encodedPath The encoded project path
     * @param sessionId The session ID
     * @return List of messages in the session
     */
    suspend fun getSessionMessages(encodedPath: String, sessionId: String): List<ClaudeMessage> {
        return client.get("/claude-history/projects/$encodedPath/sessions/$sessionId")
    }
}
