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
 * Content block in a chat message, preserving the order of text and tool usages.
 * Claude responses can interleave text and tool_use blocks, and this sealed class
 * preserves that ordering for accurate display.
 */
sealed class ContentBlock {
    /** Text content block */
    data class Text(val content: String) : ContentBlock()
    /** Tool usage block */
    data class Tool(val info: ToolUseInfo) : ContentBlock()
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
