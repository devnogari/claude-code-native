package com.claudecode.native.ui.viewmodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Utility object for parsing Claude message content into structured ContentBlocks.
 * Handles various content formats: strings, JSON primitives, arrays, and objects.
 */
@OptIn(ExperimentalUuidApi::class)
object MessageParser {

    /**
     * Extracts content blocks from message content, preserving the order of text and tool usages.
     * Returns an ordered list of ContentBlock items (Text and Tool interleaved as they appear).
     *
     * @param content The message content to parse
     * @param messageUuid Optional message UUID for generating fallback tool IDs
     */
    fun parseMessageContent(content: Any?, messageUuid: String? = null): List<ContentBlock> {
        if (content == null) return emptyList()

        val blocks = mutableListOf<ContentBlock>()

        when (content) {
            is String -> {
                // Check if string is a JSON array (e.g., image content from history reload)
                val trimmed = content.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    try {
                        val jsonArray = Json.parseToJsonElement(trimmed) as? JsonArray
                        if (jsonArray != null) {
                            for (element in jsonArray) {
                                when (element) {
                                    is JsonPrimitive -> {
                                        val cleaned = cleanThinkingTags(element.content)
                                        if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
                                    }
                                    is JsonObject -> processJsonElementToBlocks(element, blocks, messageUuid)
                                    else -> {}
                                }
                            }
                            return blocks
                        }
                    } catch (e: Exception) {
                        // Not valid JSON, treat as regular text
                    }
                }
                val cleaned = cleanThinkingTags(content)
                if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
            }
            is JsonPrimitive -> {
                val cleaned = cleanThinkingTags(content.content)
                if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
            }
            is JsonArray -> {
                for (element in content) {
                    when (element) {
                        is JsonPrimitive -> {
                            val cleaned = cleanThinkingTags(element.content)
                            if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
                        }
                        is JsonObject -> processJsonElementToBlocks(element, blocks, messageUuid)
                        else -> {} // Ignore other JSON element types
                    }
                }
            }
            is JsonObject -> processJsonElementToBlocks(content, blocks, messageUuid)
            is List<*> -> {
                for (item in content) {
                    when (item) {
                        is String -> {
                            val cleaned = cleanThinkingTags(item)
                            if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
                        }
                        is Map<*, *> -> processMapElementToBlocks(item, blocks, messageUuid)
                        else -> {} // Ignore other item types
                    }
                }
            }
            else -> {
                val cleaned = cleanThinkingTags(content.toString())
                if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
            }
        }

        return blocks
    }

    /**
     * Processes a JsonObject element and adds ContentBlock to the list (preserving order).
     * tool_result blocks are handled separately via session-level matching.
     */
    private fun processJsonElementToBlocks(
        element: JsonObject,
        blocks: MutableList<ContentBlock>,
        messageUuid: String? = null
    ) {
        val type = element["type"]?.jsonPrimitive?.content
        when (type) {
            "text" -> {
                element["text"]?.jsonPrimitive?.content?.let { text ->
                    val cleaned = cleanThinkingTags(text)
                    if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
                }
            }
            "tool_use" -> {
                val fallbackId = messageUuid?.let { "tool_${it}_${blocks.size}" } ?: "tool_${Uuid.random()}"
                val id = element["id"]?.jsonPrimitive?.content ?: fallbackId
                val name = element["name"]?.jsonPrimitive?.content ?: "Unknown"
                val input = element["input"]
                val summary = extractToolSummary(name, input)
                blocks.add(ContentBlock.Tool(ToolUseInfo(id = id, name = name, summary = summary)))
            }
            "image" -> {
                // Parse image block: {"type": "image", "source": {"type": "base64", "media_type": "...", "data": "..."}}
                element["source"]?.let { sourceElement ->
                    if (sourceElement is JsonObject) {
                        val sourceType = sourceElement["type"]?.jsonPrimitive?.content
                        if (sourceType == "base64") {
                            val mediaType = sourceElement["media_type"]?.jsonPrimitive?.content ?: "image/png"
                            val data = sourceElement["data"]?.jsonPrimitive?.content ?: ""
                            if (data.isNotEmpty()) {
                                blocks.add(ContentBlock.Image(ImageSource.Base64(data, mediaType)))
                            }
                        }
                    }
                }
            }
            // tool_result is handled via session-level matching, not added as a block
        }
    }

    /**
     * Processes a Map element and adds ContentBlock to the list (preserving order).
     * tool_result blocks are handled separately via session-level matching.
     */
    private fun processMapElementToBlocks(
        item: Map<*, *>,
        blocks: MutableList<ContentBlock>,
        messageUuid: String? = null
    ) {
        val type = item["type"] as? String
        when (type) {
            "text" -> {
                (item["text"] as? String)?.let { text ->
                    val cleaned = cleanThinkingTags(text)
                    if (cleaned.isNotBlank()) blocks.add(ContentBlock.Text(cleaned))
                }
            }
            "tool_use" -> {
                val fallbackId = messageUuid?.let { "tool_${it}_${blocks.size}" } ?: "tool_${Uuid.random()}"
                val id = (item["id"] as? String) ?: fallbackId
                val name = (item["name"] as? String) ?: "Unknown"
                val input = item["input"]
                val summary = extractToolSummaryFromMap(name, input)
                blocks.add(ContentBlock.Tool(ToolUseInfo(id = id, name = name, summary = summary)))
            }
            "image" -> {
                // Parse image block from Map
                val source = item["source"] as? Map<*, *>
                if (source != null) {
                    val sourceType = source["type"] as? String
                    if (sourceType == "base64") {
                        val mediaType = (source["media_type"] as? String) ?: "image/png"
                        val data = (source["data"] as? String) ?: ""
                        if (data.isNotEmpty()) {
                            blocks.add(ContentBlock.Image(ImageSource.Base64(data, mediaType)))
                        }
                    }
                }
            }
            // tool_result is handled via session-level matching, not added as a block
        }
    }

    /**
     * Extracts a brief summary for a tool usage from JsonElement input.
     */
    fun extractToolSummary(toolName: String, input: Any?): String {
        if (input == null) return ""

        // Handle TodoWrite which receives a JsonArray of todo items
        if (toolName == "TodoWrite" && input is JsonArray) {
            return extractTodoWriteSummary(input)
        }

        if (input !is JsonObject) return input.toString().take(100)

        return when (toolName) {
            "Read" -> input["file_path"]?.jsonPrimitive?.content ?: ""
            "Edit" -> input["file_path"]?.jsonPrimitive?.content ?: ""
            "Write" -> input["file_path"]?.jsonPrimitive?.content ?: ""
            "Grep" -> {
                val pattern = input["pattern"]?.jsonPrimitive?.content ?: ""
                val path = input["path"]?.jsonPrimitive?.content
                if (path != null) "$pattern in $path" else pattern
            }
            "Glob" -> input["pattern"]?.jsonPrimitive?.content ?: ""
            "Bash" -> {
                // Prefer description field if available (more readable)
                val description = input["description"]?.jsonPrimitive?.content
                if (!description.isNullOrBlank()) {
                    description.take(80) + if (description.length > 80) "..." else ""
                } else {
                    val cmd = input["command"]?.jsonPrimitive?.content ?: ""
                    cmd.take(80) + if (cmd.length > 80) "..." else ""
                }
            }
            "Task" -> input["description"]?.jsonPrimitive?.content ?: ""
            "WebFetch" -> input["url"]?.jsonPrimitive?.content ?: ""
            "WebSearch" -> input["query"]?.jsonPrimitive?.content ?: ""
            else -> {
                input.entries.firstOrNull()?.let { (key, value) ->
                    try {
                        val v = (value as? JsonPrimitive)?.content ?: value.toString()
                        v.take(80) + if (v.length > 80) "..." else ""
                    } catch (e: Exception) { "" }
                } ?: ""
            }
        }
    }

    /**
     * Extracts summary from TodoWrite's todo list (JsonArray).
     * Shows the first in_progress todo's activeForm, or first todo's content.
     */
    private fun extractTodoWriteSummary(todos: JsonArray): String {
        if (todos.isEmpty()) return "Empty todo list"

        // Try to find in_progress todo first
        val inProgressTodo = todos.firstOrNull { item ->
            (item as? JsonObject)?.get("status")?.jsonPrimitive?.content == "in_progress"
        } as? JsonObject

        // Fallback to first todo if no in_progress
        val targetTodo = inProgressTodo ?: (todos.firstOrNull() as? JsonObject)
        if (targetTodo == null) return "Todo list (${todos.size} items)"

        val activeForm = targetTodo["activeForm"]?.jsonPrimitive?.content
        val content = targetTodo["content"]?.jsonPrimitive?.content ?: ""
        val displayText = activeForm ?: content

        return if (displayText.length > 60) {
            displayText.take(60) + "..."
        } else {
            displayText
        }
    }

    /**
     * Extracts summary from TodoWrite's todo list (List<*>).
     * Shows the first in_progress todo's activeForm, or first todo's content.
     */
    private fun extractTodoWriteSummaryFromList(todos: List<*>): String {
        if (todos.isEmpty()) return "Empty todo list"

        // Try to find in_progress todo first
        val inProgressTodo = todos.firstOrNull { item ->
            (item as? Map<*, *>)?.get("status") == "in_progress"
        } as? Map<*, *>

        // Fallback to first todo if no in_progress
        val targetTodo = inProgressTodo ?: (todos.firstOrNull() as? Map<*, *>)
        if (targetTodo == null) return "Todo list (${todos.size} items)"

        val activeForm = targetTodo["activeForm"] as? String
        val content = targetTodo["content"] as? String ?: ""
        val displayText = activeForm ?: content

        return if (displayText.length > 60) {
            displayText.take(60) + "..."
        } else {
            displayText
        }
    }

    /**
     * Extracts a brief summary for a tool usage from Map input.
     */
    fun extractToolSummaryFromMap(toolName: String, input: Any?): String {
        if (input == null) return ""

        // Handle TodoWrite which receives a List of todo items
        if (toolName == "TodoWrite" && input is List<*>) {
            return extractTodoWriteSummaryFromList(input)
        }

        if (input !is Map<*, *>) return input.toString().take(100)

        return when (toolName) {
            "Read" -> (input["file_path"] as? String) ?: ""
            "Edit" -> (input["file_path"] as? String) ?: ""
            "Write" -> (input["file_path"] as? String) ?: ""
            "Grep" -> {
                val pattern = (input["pattern"] as? String) ?: ""
                val path = input["path"] as? String
                if (path != null) "$pattern in $path" else pattern
            }
            "Glob" -> (input["pattern"] as? String) ?: ""
            "Bash" -> {
                // Prefer description field if available (more readable)
                val description = input["description"] as? String
                if (!description.isNullOrBlank()) {
                    description.take(80) + if (description.length > 80) "..." else ""
                } else {
                    val cmd = (input["command"] as? String) ?: ""
                    cmd.take(80) + if (cmd.length > 80) "..." else ""
                }
            }
            "Task" -> (input["description"] as? String) ?: ""
            "WebFetch" -> (input["url"] as? String) ?: ""
            "WebSearch" -> (input["query"] as? String) ?: ""
            else -> {
                input.entries.firstOrNull()?.let { (_, value) ->
                    val v = value?.toString() ?: ""
                    v.take(80) + if (v.length > 80) "..." else ""
                } ?: ""
            }
        }
    }

    /**
     * Extracts text from tool_result content.
     */
    fun extractToolResultText(content: Any?): String {
        return when (content) {
            null -> ""
            is String -> content
            is JsonPrimitive -> content.content
            is JsonArray -> {
                content.mapNotNull { item ->
                    when (item) {
                        is JsonPrimitive -> item.content
                        is JsonObject -> {
                            val type = item["type"]?.jsonPrimitive?.content
                            if (type == "text") item["text"]?.jsonPrimitive?.content else null
                        }
                        else -> null
                    }
                }.joinToString("\n")
            }
            else -> content.toString()
        }
    }

    /**
     * Legacy function for simple text extraction.
     * Kept internal for potential backwards compatibility needs.
     */
    internal fun extractTextContent(content: Any?): String {
        return parseMessageContent(content)
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("\n\n") { it.content }
    }

    /**
     * Removes thinking tags from content.
     */
    fun cleanThinkingTags(text: String): String {
        return text
            .replace(Regex("<thinking>.*?</thinking>", RegexOption.DOT_MATCHES_ALL), "")
            .replace("</thinking>", "")
            .replace("<thinking>", "")
            .trim()
    }

    /**
     * Checks if the message is a compaction/summary message (system-generated).
     * These messages are generated when Claude Code session runs out of context
     * and should not be displayed as user messages.
     */
    fun isCompactionMessage(text: String): Boolean {
        return text.startsWith("This session is being continued from a previous conversation") ||
               text.startsWith("Please continue the conversation from where we left") ||
               text.contains("The conversation is summarized below:") ||
               text.contains("ran out of context")
    }

    /**
     * Extracts tool_use and tool_result items from message content into separate maps.
     * Used for cross-message matching of tools with their results.
     */
    fun extractToolsFromContent(
        content: Any?,
        toolUses: MutableMap<String, ToolUseInfo>,
        toolResults: MutableMap<String, Pair<String, Boolean>>
    ) {
        when (content) {
            is JsonArray -> {
                for (element in content) {
                    if (element is JsonObject) {
                        val type = element["type"]?.jsonPrimitive?.content
                        when (type) {
                            "tool_use" -> {
                                val id = element["id"]?.jsonPrimitive?.content ?: continue
                                val name = element["name"]?.jsonPrimitive?.content ?: "Unknown"
                                val input = element["input"]
                                val summary = extractToolSummary(name, input)
                                toolUses[id] = ToolUseInfo(id = id, name = name, summary = summary)
                            }
                            "tool_result" -> {
                                val toolUseId = element["tool_use_id"]?.jsonPrimitive?.content ?: continue
                                val resultContent = element["content"]
                                val isError = element["is_error"]?.jsonPrimitive?.content == "true"
                                val result = extractToolResultText(resultContent)
                                toolResults[toolUseId] = Pair(result, isError)
                            }
                        }
                    }
                }
            }
            is List<*> -> {
                for (item in content) {
                    if (item is Map<*, *>) {
                        val type = item["type"] as? String
                        when (type) {
                            "tool_use" -> {
                                val id = (item["id"] as? String) ?: continue
                                val name = (item["name"] as? String) ?: "Unknown"
                                val input = item["input"]
                                val summary = extractToolSummaryFromMap(name, input)
                                toolUses[id] = ToolUseInfo(id = id, name = name, summary = summary)
                            }
                            "tool_result" -> {
                                val toolUseId = (item["tool_use_id"] as? String) ?: continue
                                val resultContent = item["content"]
                                val isError = item["is_error"] == true
                                val result = when (resultContent) {
                                    is String -> resultContent
                                    is List<*> -> resultContent.mapNotNull { it?.toString() }.joinToString("\n")
                                    else -> resultContent?.toString() ?: ""
                                }
                                toolResults[toolUseId] = Pair(result, isError)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if message content contains only tool_result items (no text or tool_use).
     */
    fun hasOnlyToolResults(content: Any?): Boolean {
        var hasToolResult = false
        var hasOther = false

        when (content) {
            is JsonArray -> {
                for (element in content) {
                    if (element is JsonObject) {
                        val type = element["type"]?.jsonPrimitive?.content
                        when (type) {
                            "tool_result" -> hasToolResult = true
                            "text" -> {
                                val text = element["text"]?.jsonPrimitive?.content
                                if (!text.isNullOrBlank()) hasOther = true
                            }
                            "tool_use" -> hasOther = true
                        }
                    }
                }
            }
            is List<*> -> {
                for (item in content) {
                    if (item is Map<*, *>) {
                        val type = item["type"] as? String
                        when (type) {
                            "tool_result" -> hasToolResult = true
                            "text" -> {
                                val text = item["text"] as? String
                                if (!text.isNullOrBlank()) hasOther = true
                            }
                            "tool_use" -> hasOther = true
                        }
                    }
                }
            }
        }

        return hasToolResult && !hasOther
    }
}
