package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.model.MessageRole

/**
 * Tool usage information for display in the UI.
 *
 * @param id Unique identifier for the tool use
 * @param name Tool name (Read, Edit, Bash, etc.)
 * @param summary Brief summary of the tool input
 * @param result Full result content (shown when expanded)
 * @param isError Whether the result is an error
 */
data class ToolUseInfo(
    val id: String,
    val name: String,
    val summary: String,
    val result: String? = null,
    val isError: Boolean = false
)

/**
 * Image source for image content blocks.
 * Currently supports base64-encoded images.
 */
sealed class ImageSource {
    /**
     * Base64-encoded image data.
     * @param data Base64-encoded image bytes
     * @param mediaType MIME type (e.g., "image/png", "image/jpeg")
     */
    data class Base64(
        val data: String,
        val mediaType: String
    ) : ImageSource()
}

/**
 * Content block in a chat message, preserving the order of text and tool usages.
 * Claude responses can interleave text and tool_use blocks, and this sealed class
 * preserves that ordering for accurate display.
 */
sealed class ContentBlock {
    /** Text content block */
    data class Text(val content: String) : ContentBlock()
    /** Tool usage block */
    data class Tool(val info: ToolUseInfo) : ContentBlock()
    /** Image content block */
    data class Image(val source: ImageSource) : ContentBlock()
}

/**
 * Chat message displayed in the UI.
 *
 * @param id Unique identifier for the message
 * @param role Who sent the message (user or assistant)
 * @param blocks Ordered list of content blocks (text and tools interleaved)
 * @param isStreaming True if this message is currently being streamed
 * @param isPending True if this message is pending confirmation from server (user messages only)
 * @param gitBranch Git branch where the message was sent (from Claude CLI)
 * @param agentId Subagent ID if message is from a spawned agent
 * @param isSidechain True if message is part of a sidechain conversation
 */
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val blocks: List<ContentBlock>,
    val isStreaming: Boolean = false,
    val isPending: Boolean = false,
    val gitBranch: String? = null,
    val agentId: String? = null,
    val isSidechain: Boolean = false
) {
    /** Convenience property: concatenated text content for searching/matching */
    val content: String
        get() = blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n\n") { it.content }

    /** Convenience property: list of tools for backwards compatibility */
    val tools: List<ToolUseInfo>
        get() = blocks.filterIsInstance<ContentBlock.Tool>().map { it.info }
}

/**
 * Source of the queued message - tracks where the message originated from.
 */
enum class QueuedMessageSource {
    /** Message queued locally from this app */
    LOCAL,
    /** Message queued from Claude CLI (terminal) */
    CLI
}

/**
 * Queued message waiting to be processed.
 * Tracks both content and metadata for queue management.
 *
 * @param id Unique identifier for the queued message
 * @param content The message content
 * @param queuedAt Timestamp when the message was queued (epoch milliseconds)
 * @param source Where the message originated from
 */
data class QueuedMessage(
    val id: String,
    val content: String,
    val queuedAt: Long,
    val source: QueuedMessageSource = QueuedMessageSource.LOCAL
)

/**
 * Image attached to a message before sending.
 * Used for image preview and upload.
 *
 * @param id Unique identifier for the attached image
 * @param data Raw image bytes
 * @param mediaType MIME type (e.g., "image/png", "image/jpeg")
 * @param fileName Original file name (if available)
 * @param width Image width in pixels (if known)
 * @param height Image height in pixels (if known)
 */
data class AttachedImage(
    val id: String,
    val data: ByteArray,
    val mediaType: String,
    val fileName: String? = null,
    val width: Int? = null,
    val height: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as AttachedImage
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
