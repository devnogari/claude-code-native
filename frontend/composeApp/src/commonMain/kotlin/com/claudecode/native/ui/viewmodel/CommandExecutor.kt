package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.CommandApiInterface
import com.claudecode.native.data.model.Command
import com.claudecode.native.data.model.ExecuteCommandResponse
import com.claudecode.native.data.model.ExecuteContext
import com.claudecode.native.util.DebugLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages slash command loading and execution.
 *
 * This class handles:
 * - Loading available commands from the backend
 * - Executing builtin and custom commands
 * - Routing command results to appropriate handlers
 *
 * Usage:
 * 1. Set callbacks for message operations (onAddResultMessage, onSendMessage)
 * 2. Call loadCommands() when project changes
 * 3. Call executeCommand() when user triggers a slash command
 */
class CommandExecutor(
    private val commandApi: CommandApiInterface,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    // State flows for command availability
    private val _availableCommands = MutableStateFlow<List<Command>>(emptyList())
    val availableCommands: StateFlow<List<Command>> = _availableCommands.asStateFlow()

    private val _commandsLoading = MutableStateFlow(false)
    val commandsLoading: StateFlow<Boolean> = _commandsLoading.asStateFlow()

    // Internal job for cancellation
    private var loadCommandsJob: Job? = null

    // Callbacks for message operations (set by ChatViewModel)
    var onAddResultMessage: ((String) -> Unit)? = null
    var onSendMessage: ((String) -> Unit)? = null
    var onClearMessages: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Loads available commands from the server for the given project path.
     * Cancels any in-flight request to prevent race conditions.
     *
     * @param projectPath Path to the project for project-level commands
     */
    fun loadCommands(projectPath: String?) {
        // Cancel any previous in-flight request to prevent stale data overwriting newer data
        loadCommandsJob?.cancel()
        // Set loading state immediately before launching coroutine
        // so it's visible to callers right away
        _commandsLoading.value = true
        loadCommandsJob = scope.launch {
            try {
                val path = projectPath ?: ""
                val response = commandApi.listCommands(path)
                _availableCommands.value = response.builtIn + response.custom
                DebugLogger.d(TAG, "Loaded ${response.count} commands (${response.builtIn.size} builtin, ${response.custom.size} custom)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogger.d(TAG, "Failed to load commands: ${e.message}")
                // Don't show error to user - just use empty list
                _availableCommands.value = emptyList()
            } finally {
                _commandsLoading.value = false
            }
        }
    }

    /**
     * Executes a slash command via the API.
     *
     * @param commandName The command name (e.g., "/help")
     * @param commandPath Optional path for custom commands
     * @param args Command arguments
     * @param projectPath Current project path for context
     * @param onBuiltinResult Callback for builtin command result handling (for custom UI actions)
     */
    fun executeCommand(
        commandName: String,
        commandPath: String? = null,
        args: List<String> = emptyList(),
        projectPath: String?,
        onBuiltinResult: (ExecuteCommandResponse) -> Unit = {}
    ) {
        scope.launch {
            try {
                val response = commandApi.executeCommand(
                    commandName = commandName,
                    commandPath = commandPath,
                    args = args,
                    context = ExecuteContext(projectPath = projectPath ?: "")
                )

                when (response.type) {
                    "builtin" -> handleBuiltinCommandResult(response, onBuiltinResult)
                    "custom" -> handleCustomCommandResult(response)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError?.invoke("Command failed: ${e.message}")
            }
        }
    }

    /**
     * Clears all command state.
     * Call this when switching conversations or cleaning up.
     */
    fun clearState() {
        loadCommandsJob?.cancel()
        loadCommandsJob = null
        _availableCommands.value = emptyList()
        _commandsLoading.value = false
    }

    /**
     * Handles the result of a builtin command.
     * Most builtin commands display their results as local assistant messages.
     */
    private fun handleBuiltinCommandResult(
        response: ExecuteCommandResponse,
        onBuiltinResult: (ExecuteCommandResponse) -> Unit
    ) {
        when (response.action) {
            "clear" -> {
                onClearMessages?.invoke()
            }
            // These commands display their results as local messages (not sent to Claude)
            "help", "model", "cost", "memory", "config", "status", "rewind", "compact" -> {
                val content = response.content
                    ?: response.data?.get("content")?.toString()?.removeSurrounding("\"")
                    ?: ""
                if (content.isNotEmpty()) {
                    onAddResultMessage?.invoke(content)
                }
            }
            else -> {
                // Delegate to caller for other actions that may need custom handling
                onBuiltinResult(response)
            }
        }
    }

    /**
     * Handles the result of a custom command.
     * Custom commands return content that should be sent to Claude.
     */
    private fun handleCustomCommandResult(response: ExecuteCommandResponse) {
        response.content?.let { content ->
            if (content.isNotEmpty()) {
                // Send the processed command content as a message to Claude
                onSendMessage?.invoke(content)
            }
        }
    }
}
