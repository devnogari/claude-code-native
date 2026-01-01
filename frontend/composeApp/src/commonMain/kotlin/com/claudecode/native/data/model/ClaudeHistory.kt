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
    // Common fields
    val uuid: String? = null,
    @SerialName("parentUuid") val parentUuid: String? = null,
    val type: String,
    @SerialName("sessionId") val sessionId: String? = null,
    val timestamp: Instant? = null,
    val cwd: String? = null,
    @SerialName("gitBranch") val gitBranch: String? = null,
    @SerialName("isSidechain") val isSidechain: Boolean = false,
    @SerialName("userType") val userType: String? = null,
    val version: String? = null,
    val slug: String? = null,
    @SerialName("agentId") val agentId: String? = null, // ID for subagent messages

    // Message content (for user/assistant types)
    val message: MessageContent? = null,
    @SerialName("requestId") val requestId: String? = null,

    // Tool use result (for user type with tool results)
    @SerialName("toolUseResult") val toolUseResult: ToolUseResult? = null,

    // Queue operation fields (type="queue-operation")
    val operation: String? = null,
    val content: String? = null,

    // Meta message flag (skill content injected by Claude Code, not user-typed)
    @SerialName("isMeta") val isMeta: Boolean = false
)

@Serializable
data class MessageContent(
    val role: String,
    val content: kotlinx.serialization.json.JsonElement,
    val model: String? = null,
    val id: String? = null,
    val type: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
    val usage: Usage? = null
)

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("service_tier") val serviceTier: String? = null,
    @SerialName("cache_creation") val cacheCreation: CacheCreationUsage? = null
)

@Serializable
data class CacheCreationUsage(
    @SerialName("ephemeral_5m_input_tokens") val ephemeral5mInputTokens: Int = 0,
    @SerialName("ephemeral_1h_input_tokens") val ephemeral1hInputTokens: Int = 0
)

@Serializable
data class ToolUseResult(
    val stdout: String? = null,
    val stderr: String? = null,
    val interrupted: Boolean = false,
    @SerialName("isImage") val isImage: Boolean = false
)

/**
 * Represents the current state of a Claude session
 */
@Serializable
enum class SessionState {
    @SerialName("idle")
    IDLE,
    @SerialName("queued")
    QUEUED,
    @SerialName("streaming")
    STREAMING
}

/**
 * Represents a todo item from Claude Code's TodoWrite tool
 */
@Serializable
data class TodoItem(
    /** Task description (imperative form, e.g., "Run tests") */
    val content: String,
    /** Task status: pending, in_progress, completed */
    val status: String,
    /** Present continuous form shown during execution (e.g., "Running tests") */
    val activeForm: String? = null,
    /** Optional priority: high, medium, low */
    val priority: String? = null,
    /** Optional task ID */
    val id: String? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_COMPLETED = "completed"
    }

    val isPending: Boolean get() = status == STATUS_PENDING
    val isInProgress: Boolean get() = status == STATUS_IN_PROGRESS
    val isCompleted: Boolean get() = status == STATUS_COMPLETED
}

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
