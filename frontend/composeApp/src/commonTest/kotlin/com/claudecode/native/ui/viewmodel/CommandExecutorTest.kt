package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.CommandApiInterface
import com.claudecode.native.data.model.Command
import com.claudecode.native.data.model.ExecuteCommandResponse
import com.claudecode.native.data.model.ExecuteContext
import com.claudecode.native.data.model.ListCommandsResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for CommandExecutor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandExecutorTest {

    private class FakeCommandApi : CommandApiInterface {
        var listCommandsResponse: ListCommandsResponse = ListCommandsResponse(
            count = 0,
            builtIn = emptyList(),
            custom = emptyList()
        )
        var executeCommandResponse: ExecuteCommandResponse = ExecuteCommandResponse(
            type = "builtin",
            command = "/help",
            action = "help",
            content = "Help content"
        )
        var shouldThrow: Boolean = false
        var lastExecuteContext: ExecuteContext? = null

        override suspend fun listCommands(projectPath: String): ListCommandsResponse {
            if (shouldThrow) throw Exception("API Error")
            return listCommandsResponse
        }

        override suspend fun executeCommand(
            commandName: String,
            commandPath: String?,
            args: List<String>,
            context: ExecuteContext
        ): ExecuteCommandResponse {
            lastExecuteContext = context
            if (shouldThrow) throw Exception("Execute Error")
            return executeCommandResponse
        }
    }

    @Test
    fun `loadCommands should update availableCommands on success`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()

        val builtInCommands = listOf(
            Command(name = "/help", description = "Show help", namespace = "builtin"),
            Command(name = "/clear", description = "Clear messages", namespace = "builtin")
        )
        val customCommands = listOf(
            Command(name = "/deploy", description = "Deploy app", path = "/custom/deploy.md", namespace = "project")
        )
        fakeApi.listCommandsResponse = ListCommandsResponse(
            count = 3,
            builtIn = builtInCommands,
            custom = customCommands
        )

        val executor = CommandExecutor(fakeApi, testScope)
        executor.loadCommands("/test/project")
        testScope.advanceUntilIdle()

        assertEquals(3, executor.availableCommands.value.size)
        assertTrue(executor.availableCommands.value.containsAll(builtInCommands + customCommands))
        assertFalse(executor.commandsLoading.value)
    }

    @Test
    fun `loadCommands should handle error gracefully`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()
        fakeApi.shouldThrow = true

        val executor = CommandExecutor(fakeApi, testScope)
        executor.loadCommands("/test/project")
        testScope.advanceUntilIdle()

        assertTrue(executor.availableCommands.value.isEmpty())
        assertFalse(executor.commandsLoading.value)
    }

    @Test
    fun `loadCommands should set loading state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()

        val executor = CommandExecutor(fakeApi, testScope)

        assertFalse(executor.commandsLoading.value)
        executor.loadCommands("/test")

        // After launching but before completion
        assertTrue(executor.commandsLoading.value)

        testScope.advanceUntilIdle()
        assertFalse(executor.commandsLoading.value)
    }

    @Test
    fun `executeCommand should call onAddResultMessage for help command`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()
        fakeApi.executeCommandResponse = ExecuteCommandResponse(
            type = "builtin",
            command = "/help",
            action = "help",
            content = "Help text"
        )

        val executor = CommandExecutor(fakeApi, testScope)
        var addedMessage: String? = null
        executor.onAddResultMessage = { addedMessage = it }

        executor.executeCommand("/help", projectPath = "/test")
        testScope.advanceUntilIdle()

        assertEquals("Help text", addedMessage)
    }

    @Test
    fun `executeCommand should call onClearMessages for clear command`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()
        fakeApi.executeCommandResponse = ExecuteCommandResponse(
            type = "builtin",
            command = "/clear",
            action = "clear"
        )

        val executor = CommandExecutor(fakeApi, testScope)
        var clearCalled = false
        executor.onClearMessages = { clearCalled = true }

        executor.executeCommand("/clear", projectPath = "/test")
        testScope.advanceUntilIdle()

        assertTrue(clearCalled)
    }

    @Test
    fun `executeCommand should call onSendMessage for custom command`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()
        fakeApi.executeCommandResponse = ExecuteCommandResponse(
            type = "custom",
            command = "/deploy",
            content = "Custom command content to send"
        )

        val executor = CommandExecutor(fakeApi, testScope)
        var sentMessage: String? = null
        executor.onSendMessage = { sentMessage = it }

        executor.executeCommand("/deploy", projectPath = "/test")
        testScope.advanceUntilIdle()

        assertEquals("Custom command content to send", sentMessage)
    }

    @Test
    fun `executeCommand should call onError on failure`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()
        fakeApi.shouldThrow = true

        val executor = CommandExecutor(fakeApi, testScope)
        var errorMessage: String? = null
        executor.onError = { errorMessage = it }

        executor.executeCommand("/test", projectPath = "/test")
        testScope.advanceUntilIdle()

        assertTrue(errorMessage?.contains("Execute Error") == true)
    }

    @Test
    fun `executeCommand should pass project path in context`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()

        val executor = CommandExecutor(fakeApi, testScope)
        executor.executeCommand("/test", projectPath = "/my/project")
        testScope.advanceUntilIdle()

        assertEquals("/my/project", fakeApi.lastExecuteContext?.projectPath)
    }

    @Test
    fun `executeCommand should use empty string for null project path`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()

        val executor = CommandExecutor(fakeApi, testScope)
        executor.executeCommand("/test", projectPath = null)
        testScope.advanceUntilIdle()

        assertEquals("", fakeApi.lastExecuteContext?.projectPath)
    }

    @Test
    fun `clearState should reset all state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()
        fakeApi.listCommandsResponse = ListCommandsResponse(
            count = 1,
            builtIn = listOf(Command(name = "/help", description = "Help", namespace = "builtin")),
            custom = emptyList()
        )

        val executor = CommandExecutor(fakeApi, testScope)
        executor.loadCommands("/test")
        testScope.advanceUntilIdle()

        assertEquals(1, executor.availableCommands.value.size)

        executor.clearState()

        assertTrue(executor.availableCommands.value.isEmpty())
        assertFalse(executor.commandsLoading.value)
    }

    @Test
    fun `executeCommand should call onBuiltinResult callback for unknown actions`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()
        fakeApi.executeCommandResponse = ExecuteCommandResponse(
            type = "builtin",
            command = "/custom",
            action = "custom_action",
            content = "Custom action content"
        )

        val executor = CommandExecutor(fakeApi, testScope)
        var callbackResponse: ExecuteCommandResponse? = null

        executor.executeCommand(
            "/custom",
            projectPath = "/test",
            onBuiltinResult = { callbackResponse = it }
        )
        testScope.advanceUntilIdle()

        assertEquals("custom_action", callbackResponse?.action)
    }

    @Test
    fun `builtin commands like model and cost and memory should add result message`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeApi = FakeCommandApi()

        val actions = listOf("model", "cost", "memory", "config", "status", "rewind", "compact")

        for (action in actions) {
            fakeApi.executeCommandResponse = ExecuteCommandResponse(
                type = "builtin",
                command = "/$action",
                action = action,
                content = "$action content"
            )

            val executor = CommandExecutor(fakeApi, testScope)
            var addedMessage: String? = null
            executor.onAddResultMessage = { addedMessage = it }

            executor.executeCommand("/$action", projectPath = "/test")
            testScope.advanceUntilIdle()

            assertEquals("$action content", addedMessage, "Failed for action: $action")
        }
    }
}
