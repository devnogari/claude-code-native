package com.claudecode.native.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a slash command available in the chat interface.
 */
@Serializable
data class Command(
    val name: String,
    val path: String? = null,
    @SerialName("relativePath")
    val relativePath: String? = null,
    val description: String,
    val namespace: String, // "builtin", "project", "user"
    val metadata: Map<String, JsonElement>? = null
)

/**
 * Request body for listing available commands.
 */
@Serializable
data class ListCommandsRequest(
    val projectPath: String
)

/**
 * Response containing available commands.
 */
@Serializable
data class ListCommandsResponse(
    val builtIn: List<Command>,
    val custom: List<Command>,
    val count: Int
)

/**
 * Context information for command execution.
 */
@Serializable
data class ExecuteContext(
    val projectPath: String? = null,
    val model: String? = null,
    val provider: String? = null
)

/**
 * Request body for executing a command.
 */
@Serializable
data class ExecuteCommandRequest(
    val commandName: String,
    val commandPath: String? = null,
    val args: List<String> = emptyList(),
    val context: ExecuteContext = ExecuteContext()
)

/**
 * Response from command execution.
 */
@Serializable
data class ExecuteCommandResponse(
    val type: String, // "builtin" or "custom"
    val command: String,
    val action: String? = null,
    val content: String? = null,
    val data: Map<String, JsonElement>? = null,
    val hasFileIncludes: Boolean = false,
    val hasBashCommands: Boolean = false,
    val metadata: Map<String, JsonElement>? = null
)
