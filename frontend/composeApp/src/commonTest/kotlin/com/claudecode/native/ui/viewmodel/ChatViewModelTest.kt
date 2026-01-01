package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.data.websocket.IncomingMessage
import com.claudecode.native.data.websocket.MessageType
import com.claudecode.native.data.websocket.OutgoingMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for ChatViewModel data models and ContentBlock structures.
 *
 * Note: Full ViewModel testing would require interface-based mocking.
 * These tests focus on the data classes and ContentBlock logic which
 * are critical for correct message display.
 */
class ChatViewModelTest {

    // =====================================
    // ToolUseInfo Tests
    // =====================================

    @Test
    fun `ToolUseInfo should store all fields correctly`() {
        val toolInfo = ToolUseInfo(
            id = "tool_123",
            name = "Read",
            summary = "/path/to/file.kt",
            result = "file contents",
            isError = false
        )

        assertEquals("tool_123", toolInfo.id)
        assertEquals("Read", toolInfo.name)
        assertEquals("/path/to/file.kt", toolInfo.summary)
        assertEquals("file contents", toolInfo.result)
        assertFalse(toolInfo.isError)
    }

    @Test
    fun `ToolUseInfo with null result should work`() {
        val toolInfo = ToolUseInfo(
            id = "tool_123",
            name = "Bash",
            summary = "npm install"
        )

        assertEquals("tool_123", toolInfo.id)
        assertEquals("Bash", toolInfo.name)
        assertEquals("npm install", toolInfo.summary)
        assertNull(toolInfo.result)
        assertFalse(toolInfo.isError)
    }

    @Test
    fun `ToolUseInfo with error should set isError true`() {
        val toolInfo = ToolUseInfo(
            id = "tool_error",
            name = "Bash",
            summary = "invalid command",
            result = "Command not found",
            isError = true
        )

        assertTrue(toolInfo.isError)
        assertEquals("Command not found", toolInfo.result)
    }

    @Test
    fun `ToolUseInfo copy should preserve values`() {
        val original = ToolUseInfo(
            id = "tool_1",
            name = "Edit",
            summary = "file.kt"
        )

        val updated = original.copy(result = "Success", isError = false)

        assertEquals("tool_1", updated.id)
        assertEquals("Edit", updated.name)
        assertEquals("file.kt", updated.summary)
        assertEquals("Success", updated.result)
        assertFalse(updated.isError)
    }

    // =====================================
    // ContentBlock Tests
    // =====================================

    @Test
    fun `ContentBlock Text should contain text content`() {
        val textBlock = ContentBlock.Text("Hello World")

        assertTrue(textBlock is ContentBlock.Text)
        assertEquals("Hello World", textBlock.content)
    }

    @Test
    fun `ContentBlock Text with empty content should work`() {
        val textBlock = ContentBlock.Text("")

        assertEquals("", textBlock.content)
    }

    @Test
    fun `ContentBlock Text with multiline content should preserve newlines`() {
        val content = "Line 1\nLine 2\nLine 3"
        val textBlock = ContentBlock.Text(content)

        assertEquals(content, textBlock.content)
        assertTrue(textBlock.content.contains("\n"))
    }

    @Test
    fun `ContentBlock Tool should contain tool info`() {
        val toolInfo = ToolUseInfo(
            id = "tool_1",
            name = "Bash",
            summary = "ls -la"
        )
        val toolBlock = ContentBlock.Tool(toolInfo)

        assertTrue(toolBlock is ContentBlock.Tool)
        assertEquals("Bash", toolBlock.info.name)
        assertEquals("ls -la", toolBlock.info.summary)
    }

    @Test
    fun `ContentBlock Tool with result should preserve result`() {
        val toolInfo = ToolUseInfo(
            id = "tool_1",
            name = "Read",
            summary = "config.json",
            result = "{\"key\": \"value\"}"
        )
        val toolBlock = ContentBlock.Tool(toolInfo)

        assertEquals("{\"key\": \"value\"}", toolBlock.info.result)
    }

    // =====================================
    // ChatMessage Tests
    // =====================================

    @Test
    fun `ChatMessage content property should return empty for empty blocks`() {
        val message = ChatMessage(
            id = "msg_1",
            role = MessageRole.USER,
            blocks = emptyList()
        )

        assertEquals("", message.content)
    }

    @Test
    fun `ChatMessage content property should return single text block content`() {
        val blocks = listOf(ContentBlock.Text("Hello"))

        val message = ChatMessage(
            id = "msg_1",
            role = MessageRole.USER,
            blocks = blocks
        )

        assertEquals("Hello", message.content)
    }

    @Test
    fun `ChatMessage content property should concatenate text blocks`() {
        val blocks = listOf(
            ContentBlock.Text("First paragraph"),
            ContentBlock.Tool(ToolUseInfo("1", "Read", "file.kt")),
            ContentBlock.Text("Second paragraph")
        )

        val message = ChatMessage(
            id = "msg_1",
            role = MessageRole.ASSISTANT,
            blocks = blocks
        )

        assertEquals("First paragraph\n\nSecond paragraph", message.content)
    }

    @Test
    fun `ChatMessage content property should skip tool blocks`() {
        val blocks = listOf(
            ContentBlock.Tool(ToolUseInfo("1", "Bash", "echo test")),
            ContentBlock.Tool(ToolUseInfo("2", "Read", "file.txt"))
        )

        val message = ChatMessage(
            id = "msg_1",
            role = MessageRole.ASSISTANT,
            blocks = blocks
        )

        assertEquals("", message.content)
    }

    @Test
    fun `ChatMessage tools property should extract tool blocks`() {
        val tool1 = ToolUseInfo("1", "Read", "file1.kt")
        val tool2 = ToolUseInfo("2", "Bash", "npm test")

        val blocks = listOf(
            ContentBlock.Text("Text"),
            ContentBlock.Tool(tool1),
            ContentBlock.Tool(tool2)
        )

        val message = ChatMessage(
            id = "msg_1",
            role = MessageRole.ASSISTANT,
            blocks = blocks
        )

        assertEquals(2, message.tools.size)
        assertEquals("Read", message.tools[0].name)
        assertEquals("Bash", message.tools[1].name)
    }

    @Test
    fun `ChatMessage tools property should return empty for no tools`() {
        val blocks = listOf(
            ContentBlock.Text("Just text content")
        )

        val message = ChatMessage(
            id = "msg_1",
            role = MessageRole.USER,
            blocks = blocks
        )

        assertTrue(message.tools.isEmpty())
    }

    @Test
    fun `ChatMessage with user role should be marked as user`() {
        val message = ChatMessage(
            id = "msg_1",
            role = MessageRole.USER,
            blocks = listOf(ContentBlock.Text("Hello"))
        )

        assertEquals(MessageRole.USER, message.role)
    }

    @Test
    fun `ChatMessage with assistant role should be marked as assistant`() {
        val message = ChatMessage(
            id = "msg_1",
            role = MessageRole.ASSISTANT,
            blocks = listOf(ContentBlock.Text("Hello"))
        )

        assertEquals(MessageRole.ASSISTANT, message.role)
    }

    @Test
    fun `ChatMessage isPending should default to false`() {
        val message = ChatMessage(
            id = "msg_1",
            role = MessageRole.USER,
            blocks = listOf(ContentBlock.Text("Hello"))
        )

        assertFalse(message.isPending)
    }

    @Test
    fun `ChatMessage isPending should be settable`() {
        val message = ChatMessage(
            id = "msg_1",
            role = MessageRole.USER,
            blocks = listOf(ContentBlock.Text("Hello")),
            isPending = true
        )

        assertTrue(message.isPending)
    }

    // =====================================
    // Message Role Tests
    // =====================================

    @Test
    fun `MessageRole USER should have correct value`() {
        assertEquals(MessageRole.USER, MessageRole.valueOf("USER"))
    }

    @Test
    fun `MessageRole ASSISTANT should have correct value`() {
        assertEquals(MessageRole.ASSISTANT, MessageRole.valueOf("ASSISTANT"))
    }

    @Test
    fun `MessageRole SYSTEM should have correct value`() {
        assertEquals(MessageRole.SYSTEM, MessageRole.valueOf("SYSTEM"))
    }

    // =====================================
    // WebSocket Message Tests
    // =====================================

    @Test
    fun `OutgoingMessage chat factory should create correct message`() {
        val message = OutgoingMessage.chat("Hello world")

        assertEquals(MessageType.CHAT, message.type)
        assertEquals("Hello world", message.content)
    }

    @Test
    fun `OutgoingMessage stop factory should create correct message`() {
        val message = OutgoingMessage.stop()

        assertEquals(MessageType.STOP, message.type)
        assertNull(message.content)
    }

    @Test
    fun `OutgoingMessage ping factory should create correct message`() {
        val message = OutgoingMessage.ping()

        assertEquals(MessageType.PING, message.type)
        assertNull(message.content)
    }

    @Test
    fun `IncomingMessage stream should parse content`() {
        val message = IncomingMessage(
            type = MessageType.STREAM,
            content = "Hello from Claude"
        )

        assertEquals(MessageType.STREAM, message.type)
        assertEquals("Hello from Claude", message.content)
    }

    @Test
    fun `IncomingMessage error should parse error field`() {
        val message = IncomingMessage(
            type = MessageType.ERROR,
            error = "Connection failed"
        )

        assertEquals(MessageType.ERROR, message.type)
        assertEquals("Connection failed", message.error)
    }

    @Test
    fun `IncomingMessage complete should have no content`() {
        val message = IncomingMessage(
            type = MessageType.COMPLETE
        )

        assertEquals(MessageType.COMPLETE, message.type)
        assertNull(message.content)
    }

    @Test
    fun `IncomingMessage with all fields should parse correctly`() {
        val message = IncomingMessage(
            type = MessageType.STATUS,
            conversationId = "conv123",
            content = "processing",
            status = "active"
        )

        assertEquals(MessageType.STATUS, message.type)
        assertEquals("conv123", message.conversationId)
        assertEquals("processing", message.content)
        assertEquals("active", message.status)
    }

    // =====================================
    // MessageType Tests
    // =====================================

    @Test
    fun `MessageType should have all expected types`() {
        assertEquals("chat", MessageType.CHAT)
        assertEquals("stream", MessageType.STREAM)
        assertEquals("status", MessageType.STATUS)
        assertEquals("error", MessageType.ERROR)
        assertEquals("stop", MessageType.STOP)
        assertEquals("complete", MessageType.COMPLETE)
        assertEquals("ping", MessageType.PING)
        assertEquals("pong", MessageType.PONG)
    }
}
