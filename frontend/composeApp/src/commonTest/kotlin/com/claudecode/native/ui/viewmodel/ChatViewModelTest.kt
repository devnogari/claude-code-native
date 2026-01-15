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

    // =====================================
    // normalizeForComparison Tests
    // =====================================

    @Test
    fun `normalizeForComparison should strip single at mention from beginning`() {
        val input = "@/tmp/claude-image-123.png image test"
        val result = ChatViewModel.normalizeForComparison(input)
        assertEquals("image test", result)
    }

    @Test
    fun `normalizeForComparison should strip multiple at mentions from beginning`() {
        val input = "@/tmp/image1.png @/tmp/image2.jpg hello world"
        val result = ChatViewModel.normalizeForComparison(input)
        assertEquals("hello world", result)
    }

    @Test
    fun `normalizeForComparison should not strip at mentions in middle of content`() {
        val input = "check out @user for more info"
        val result = ChatViewModel.normalizeForComparison(input)
        assertEquals("check out @user for more info", result)
    }

    @Test
    fun `normalizeForComparison should handle content without at mentions`() {
        val input = "normal message content"
        val result = ChatViewModel.normalizeForComparison(input)
        assertEquals("normal message content", result)
    }

    @Test
    fun `normalizeForComparison should normalize whitespace`() {
        val input = "  multiple   spaces   here  "
        val result = ChatViewModel.normalizeForComparison(input)
        assertEquals("multiple spaces here", result)
    }

    @Test
    fun `normalizeForComparison should handle at mention with trailing whitespace`() {
        val input = "@/path/to/file.png   message with extra spaces"
        val result = ChatViewModel.normalizeForComparison(input)
        assertEquals("message with extra spaces", result)
    }

    @Test
    fun `normalizeForComparison should match hashes for image message`() {
        // When user sends "image test" with image, pending hash is computed from "image test"
        val pendingContent = "image test"
        val pendingHash = ChatViewModel.normalizeForComparison(pendingContent).hashCode()

        // When history watch receives, it includes the @ mention
        val historyContent = "@/tmp/claude-image-abc123.png image test"
        val historyHash = ChatViewModel.normalizeForComparison(historyContent).hashCode()

        assertEquals(pendingHash, historyHash, "Hashes should match after normalization")
    }

    // =====================================
    // QueuedMessage Tests
    // =====================================

    @Test
    fun `QueuedMessage should store all fields correctly`() {
        val queuedMessage = QueuedMessage(
            id = "queue_123",
            content = "Hello World",
            queuedAt = 1234567890L,
            source = QueuedMessageSource.LOCAL
        )

        assertEquals("queue_123", queuedMessage.id)
        assertEquals("Hello World", queuedMessage.content)
        assertEquals(1234567890L, queuedMessage.queuedAt)
        assertEquals(QueuedMessageSource.LOCAL, queuedMessage.source)
        assertTrue(queuedMessage.images.isEmpty())
    }

    @Test
    fun `QueuedMessage with CLI source should be distinguishable`() {
        val localMessage = QueuedMessage(
            id = "local_1",
            content = "Local message",
            queuedAt = 1000L,
            source = QueuedMessageSource.LOCAL
        )

        val cliMessage = QueuedMessage(
            id = "cli_1",
            content = "CLI message",
            queuedAt = 2000L,
            source = QueuedMessageSource.CLI
        )

        assertEquals(QueuedMessageSource.LOCAL, localMessage.source)
        assertEquals(QueuedMessageSource.CLI, cliMessage.source)
    }

    @Test
    fun `QueuedMessage with images should store images`() {
        val image = AttachedImage(
            id = "img_1",
            data = ByteArray(10),
            mediaType = "image/png"
        )

        val queuedMessage = QueuedMessage(
            id = "queue_with_image",
            content = "Message with image",
            queuedAt = 1000L,
            images = listOf(image)
        )

        assertEquals(1, queuedMessage.images.size)
        assertEquals("img_1", queuedMessage.images[0].id)
    }

    @Test
    fun `QueuedMessageSource should have LOCAL and CLI values`() {
        assertEquals(QueuedMessageSource.LOCAL, QueuedMessageSource.valueOf("LOCAL"))
        assertEquals(QueuedMessageSource.CLI, QueuedMessageSource.valueOf("CLI"))
    }

    // =====================================
    // Conversation-scoped Queue Logic Tests
    // =====================================

    @Test
    fun `filtering queue by conversationId should return only matching messages`() {
        // Simulating Map<ConversationId, List<QueuedMessage>> behavior
        val queueMap = mapOf(
            "conv_A" to listOf(
                QueuedMessage("a1", "Message A1", 1000L),
                QueuedMessage("a2", "Message A2", 2000L)
            ),
            "conv_B" to listOf(
                QueuedMessage("b1", "Message B1", 3000L)
            )
        )

        val currentConversationId = "conv_A"
        val currentQueue = queueMap[currentConversationId] ?: emptyList()

        assertEquals(2, currentQueue.size)
        assertEquals("a1", currentQueue[0].id)
        assertEquals("a2", currentQueue[1].id)
    }

    @Test
    fun `switching conversation should show different queue`() {
        val queueMap = mutableMapOf(
            "conv_A" to listOf(QueuedMessage("a1", "A message", 1000L)),
            "conv_B" to listOf(QueuedMessage("b1", "B message", 2000L))
        )

        // Initially on conv_A
        var currentConversationId = "conv_A"
        var visibleQueue = queueMap[currentConversationId] ?: emptyList()
        assertEquals(1, visibleQueue.size)
        assertEquals("a1", visibleQueue[0].id)

        // Switch to conv_B
        currentConversationId = "conv_B"
        visibleQueue = queueMap[currentConversationId] ?: emptyList()
        assertEquals(1, visibleQueue.size)
        assertEquals("b1", visibleQueue[0].id)

        // conv_A queue should still exist
        val convAQueue = queueMap["conv_A"] ?: emptyList()
        assertEquals(1, convAQueue.size)
        assertEquals("a1", convAQueue[0].id)
    }

    @Test
    fun `adding to queue should only affect current conversation`() {
        val queueMap = mutableMapOf<String, List<QueuedMessage>>(
            "conv_A" to listOf(QueuedMessage("a1", "A message", 1000L)),
            "conv_B" to emptyList()
        )

        val currentConversationId = "conv_A"
        val newMessage = QueuedMessage("a2", "New A message", 2000L)

        // Add to current conversation only
        queueMap[currentConversationId] = (queueMap[currentConversationId] ?: emptyList()) + newMessage

        // conv_A should have 2 messages
        assertEquals(2, queueMap["conv_A"]?.size)

        // conv_B should still be empty
        assertEquals(0, queueMap["conv_B"]?.size)
    }

    @Test
    fun `removing from queue should only affect current conversation`() {
        val queueMap = mutableMapOf(
            "conv_A" to listOf(
                QueuedMessage("a1", "A message 1", 1000L),
                QueuedMessage("a2", "A message 2", 2000L)
            ),
            "conv_B" to listOf(QueuedMessage("b1", "B message", 3000L))
        )

        val currentConversationId = "conv_A"
        val messageIdToRemove = "a1"

        // Remove from current conversation only
        queueMap[currentConversationId] = queueMap[currentConversationId]?.filter { it.id != messageIdToRemove } ?: emptyList()

        // conv_A should have 1 message left
        assertEquals(1, queueMap["conv_A"]?.size)
        assertEquals("a2", queueMap["conv_A"]?.get(0)?.id)

        // conv_B should be unaffected
        assertEquals(1, queueMap["conv_B"]?.size)
        assertEquals("b1", queueMap["conv_B"]?.get(0)?.id)
    }

    @Test
    fun `clearing local messages should only affect current conversation`() {
        val queueMap = mutableMapOf(
            "conv_A" to listOf(
                QueuedMessage("a1", "Local A", 1000L, QueuedMessageSource.LOCAL),
                QueuedMessage("a2", "CLI A", 2000L, QueuedMessageSource.CLI)
            ),
            "conv_B" to listOf(
                QueuedMessage("b1", "Local B", 3000L, QueuedMessageSource.LOCAL)
            )
        )

        val currentConversationId = "conv_A"

        // Clear only local messages from current conversation
        queueMap[currentConversationId] = queueMap[currentConversationId]?.filter {
            it.source == QueuedMessageSource.CLI
        } ?: emptyList()

        // conv_A should only have CLI message
        assertEquals(1, queueMap["conv_A"]?.size)
        assertEquals(QueuedMessageSource.CLI, queueMap["conv_A"]?.get(0)?.source)

        // conv_B should be unaffected
        assertEquals(1, queueMap["conv_B"]?.size)
        assertEquals(QueuedMessageSource.LOCAL, queueMap["conv_B"]?.get(0)?.source)
    }

    @Test
    fun `new conversation should have empty queue`() {
        val queueMap = mutableMapOf(
            "conv_A" to listOf(QueuedMessage("a1", "A message", 1000L))
        )

        val newConversationId = "conv_new"
        val newConvQueue = queueMap[newConversationId] ?: emptyList()

        assertTrue(newConvQueue.isEmpty())
    }

    // =====================================
    // isFilesystemSessionId Tests
    // =====================================

    @Test
    fun `isFilesystemSessionId should return false for standard conversation ID`() {
        val conversationId = "abc123"
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertFalse(result)
    }

    @Test
    fun `isFilesystemSessionId should return false for UUID-style conversation ID`() {
        val conversationId = "550e8400-e29b-41d4-a716-446655440000"
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertFalse(result)
    }

    @Test
    fun `isFilesystemSessionId should return true for filesystem session ID`() {
        val conversationId = "session123?project=encodedPath"
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertTrue(result)
    }

    @Test
    fun `isFilesystemSessionId should return true for filesystem session with complex encoded path`() {
        val conversationId = "abc123?project=%2FUsers%2Ftest%2Fmy-project"
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertTrue(result)
    }

    @Test
    fun `isFilesystemSessionId should return false for empty string`() {
        val conversationId = ""
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertFalse(result)
    }

    @Test
    fun `isFilesystemSessionId should return true for just project marker`() {
        // Edge case: just the marker without session ID (unlikely but possible)
        val conversationId = "?project="
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertTrue(result)
    }

    @Test
    fun `isFilesystemSessionId should return false for similar but different pattern`() {
        // Should not match "project=" without the "?"
        val conversationId = "session123project=encodedPath"
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertFalse(result)
    }

    @Test
    fun `isFilesystemSessionId should return false for project in middle without question mark`() {
        val conversationId = "session123&project=encodedPath"
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertFalse(result)
    }

    @Test
    fun `isFilesystemSessionId should return true for project marker anywhere in string`() {
        // The contains check should find it anywhere
        val conversationId = "prefix?project=path/suffix"
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertTrue(result)
    }

    @Test
    fun `isFilesystemSessionId should be case sensitive`() {
        // Should not match uppercase PROJECT
        val conversationId = "session123?PROJECT=encodedPath"
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertFalse(result)
    }

    @Test
    fun `isFilesystemSessionId should return true for multiple query parameters`() {
        // Real-world case with additional query params
        val conversationId = "session123?project=path&other=value"
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertTrue(result)
    }

    @Test
    fun `isFilesystemSessionId should return false for question mark without project`() {
        val conversationId = "session123?other=value"
        val result = ChatViewModel.isFilesystemSessionId(conversationId)
        assertFalse(result)
    }
}
