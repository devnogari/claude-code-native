package com.claudecode.native.data.api

import com.claudecode.native.data.model.ExecuteCommandRequest
import com.claudecode.native.data.model.ExecuteCommandResponse
import com.claudecode.native.data.model.ExecuteContext
import com.claudecode.native.data.model.ListCommandsRequest
import com.claudecode.native.data.model.ListCommandsResponse

/**
 * Interface for slash command operations.
 * Enables testing with fake/mock implementations.
 */
interface CommandApiInterface {
    /**
     * Lists all available commands (built-in and custom).
     *
     * @param projectPath Path to the current project for project-level commands
     * @return ListCommandsResponse with built-in and custom commands
     */
    suspend fun listCommands(projectPath: String): ListCommandsResponse

    /**
     * Executes a slash command.
     *
     * @param commandName The command name (e.g., "/help")
     * @param commandPath Optional path for custom commands
     * @param args Command arguments
     * @param context Execution context (project path, model, etc.)
     * @return ExecuteCommandResponse with result
     */
    suspend fun executeCommand(
        commandName: String,
        commandPath: String? = null,
        args: List<String> = emptyList(),
        context: ExecuteContext = ExecuteContext()
    ): ExecuteCommandResponse
}

/**
 * API client for slash command operations.
 * Handles command listing and execution.
 */
class CommandApi(private val client: ApiClient) : CommandApiInterface {

    override suspend fun listCommands(projectPath: String): ListCommandsResponse {
        return client.post("/commands/list", ListCommandsRequest(projectPath))
    }

    override suspend fun executeCommand(
        commandName: String,
        commandPath: String?,
        args: List<String>,
        context: ExecuteContext
    ): ExecuteCommandResponse {
        return client.post(
            "/commands/execute",
            ExecuteCommandRequest(
                commandName = commandName,
                commandPath = commandPath,
                args = args,
                context = context
            )
        )
    }
}
