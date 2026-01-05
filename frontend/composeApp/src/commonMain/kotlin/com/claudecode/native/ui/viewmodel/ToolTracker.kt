package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.model.ClaudeMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages session-level tool tracking for matching tool_use with tool_result across messages.
 *
 * This class is thread-safe and uses mutex protection for all map operations.
 * Tool uses and results are tracked separately, then matched when needed.
 *
 * Typical flow:
 * 1. Messages loaded -> extractAndTrackTools() called
 * 2. New messages arrive -> addToolsFromContent() called
 * 3. UI renders -> getMatchedToolUses() or updateBlocksWithToolResults() called
 */
class ToolTracker {

    // Session-level tool tracking maps
    // Using mutableMapOf with Mutex for thread safety across WebSocket and HistoryWatch handlers
    // (ConcurrentHashMap is not available in Kotlin Multiplatform)
    private val toolUses = mutableMapOf<String, ToolUseInfo>()
    private val toolResults = mutableMapOf<String, Pair<String, Boolean>>()

    private val mutex = Mutex()

    /**
     * Clears all tracked tools and results.
     * Call this when switching conversations or starting fresh.
     */
    suspend fun clearState() {
        mutex.withLock {
            toolUses.clear()
            toolResults.clear()
        }
    }

    /**
     * Extracts and tracks tools from a list of Claude messages.
     * This rebuilds the session-level tool tracking from scratch.
     *
     * @param messages List of Claude messages to extract tools from
     */
    suspend fun extractAndTrackTools(messages: List<ClaudeMessage>) {
        mutex.withLock {
            toolUses.clear()
            toolResults.clear()

            for (message in messages) {
                MessageParser.extractToolsFromContent(message.content, toolUses, toolResults)
            }

            // Match results to tool uses
            matchToolResultsInternal()
        }
    }

    /**
     * Adds tools from content to the tracking maps.
     * Use this for incrementally adding tools from new messages.
     *
     * @param content The message content to extract tools from
     */
    suspend fun addToolsFromContent(content: Any?) {
        mutex.withLock {
            MessageParser.extractToolsFromContent(content, toolUses, toolResults)
            matchToolResultsInternal()
        }
    }

    /**
     * Adds tools from multiple contents at once.
     * More efficient than calling addToolsFromContent() multiple times.
     *
     * @param contents List of message contents to extract tools from
     */
    suspend fun addToolsFromContents(contents: List<Any?>) {
        mutex.withLock {
            for (content in contents) {
                MessageParser.extractToolsFromContent(content, toolUses, toolResults)
            }
            matchToolResultsInternal()
        }
    }

    /**
     * Extracts tools from content and puts them into the provided maps.
     * This is useful when you need to track tools from older messages separately.
     *
     * @param content The message content to extract tools from
     * @param targetToolUses Map to put tool uses into
     * @param targetToolResults Map to put tool results into
     */
    fun extractToolsToMaps(
        content: Any?,
        targetToolUses: MutableMap<String, ToolUseInfo>,
        targetToolResults: MutableMap<String, Pair<String, Boolean>>
    ) {
        MessageParser.extractToolsFromContent(content, targetToolUses, targetToolResults)
    }

    /**
     * Merges external tool maps into the session tracking and matches results.
     *
     * @param externalToolUses Tool uses to merge
     * @param externalToolResults Tool results to merge
     */
    suspend fun mergeAndMatchTools(
        externalToolUses: Map<String, ToolUseInfo>,
        externalToolResults: Map<String, Pair<String, Boolean>>
    ) {
        mutex.withLock {
            toolUses.putAll(externalToolUses)
            toolResults.putAll(externalToolResults)
            matchToolResultsInternal()
        }
    }

    /**
     * Gets a snapshot of matched tool uses (tool uses with their results merged in).
     * Safe to call from any thread.
     *
     * @return Map of tool IDs to ToolUseInfo (with results populated)
     */
    suspend fun getMatchedToolUses(): Map<String, ToolUseInfo> {
        return mutex.withLock {
            toolUses.toMap()
        }
    }

    /**
     * Updates content blocks with tool results from the session tracking.
     * Tool blocks that have matching results will be updated with the result info.
     *
     * @param blocks List of content blocks to update
     * @return Updated list with tool results merged in
     */
    suspend fun updateBlocksWithToolResults(blocks: List<ContentBlock>): List<ContentBlock> {
        val toolUsesSnapshot = mutex.withLock { toolUses.toMap() }
        return updateBlocksWithToolResultsSync(blocks, toolUsesSnapshot)
    }

    /**
     * Updates content blocks with tool results using a provided tool uses map.
     * This is a synchronous version that doesn't need to acquire the mutex.
     *
     * @param blocks List of content blocks to update
     * @param matchedToolUses Map of tool IDs to ToolUseInfo to use for matching
     * @return Updated list with tool results merged in
     */
    fun updateBlocksWithToolResultsSync(
        blocks: List<ContentBlock>,
        matchedToolUses: Map<String, ToolUseInfo>
    ): List<ContentBlock> {
        return blocks.map { block ->
            when (block) {
                is ContentBlock.Tool -> {
                    val toolWithResult = matchedToolUses[block.info.id]
                    if (toolWithResult != null) ContentBlock.Tool(toolWithResult) else block
                }
                else -> block
            }
        }
    }

    /**
     * Matches results from already-extracted tool maps.
     * Updates tool uses in the provided map with their corresponding results.
     *
     * @param targetToolUses Map of tool uses to update (modified in place)
     * @param targetToolResults Map of tool results to match from
     */
    fun matchResultsInMaps(
        targetToolUses: MutableMap<String, ToolUseInfo>,
        targetToolResults: Map<String, Pair<String, Boolean>>
    ) {
        for ((toolId, resultPair) in targetToolResults) {
            val (result, isError) = resultPair
            if (targetToolUses.containsKey(toolId)) {
                targetToolUses[toolId] = targetToolUses[toolId]!!.copy(result = result, isError = isError)
            }
        }
    }

    /**
     * Internal method to match results to tool uses.
     * Must be called while holding the mutex.
     */
    private fun matchToolResultsInternal() {
        for ((toolId, resultPair) in toolResults) {
            val (result, isError) = resultPair
            if (toolUses.containsKey(toolId) && toolUses[toolId]?.result == null) {
                toolUses[toolId] = toolUses[toolId]!!.copy(result = result, isError = isError)
            }
        }
    }
}
