package com.claudecode.native.data.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Claude Code project discovered from ~/.claude/projects/
 */
@Serializable
data class ClaudeProject(
    val id: String,
    val name: String,
    val path: String,
    @SerialName("encoded_path") val encodedPath: String,
    val sessions: List<ClaudeSession> = emptyList(),
    @SerialName("last_accessed") val lastAccessed: Instant? = null
)

/**
 * Represents a conversation session in Claude Code
 */
@Serializable
data class ClaudeSession(
    val id: String,
    val filename: String,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("first_message") val firstMessage: String = "",
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("created_at") val createdAt: Instant? = null,
    @SerialName("updated_at") val updatedAt: Instant? = null,
    @SerialName("source_encoded_path") val sourceEncodedPath: String? = null // Actual directory where session file resides (for inherited sessions)
)

/**
 * Represents a message in a Claude Code conversation
 */
@Serializable
data class ClaudeMessage(
    val type: String,
    @SerialName("sessionId") val sessionId: String? = null,
    val timestamp: Instant? = null,
    val message: MessageContent? = null,
    val cwd: String? = null,
    @SerialName("parentMsgId") val parentMsgId: String? = null,
    // Queue operation fields (type="queue-operation")
    val operation: String? = null, // "enqueue", "dequeue", etc.
    val content: String? = null    // Queued message content
)

@Serializable
data class MessageContent(
    val role: String,
    val content: kotlinx.serialization.json.JsonElement
)

/**
 * Paginated response for Claude session messages
 */
@Serializable
data class PaginatedClaudeMessagesResponse(
    val messages: List<ClaudeMessage>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    @SerialName("has_more") val hasMore: Boolean
)
