package com.claudecode.native.ui.viewmodel

import com.claudecode.native.ViewModelTestBase
import com.claudecode.native.data.websocket.IncomingMessage
import com.claudecode.native.data.websocket.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for WebSocketMessageHandler.
 * Validates message categorization and callback invocation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketMessageHandlerTest : ViewModelTestBase() {

    private lateinit var handler: WebSocketMessageHandler
    private val receivedCallbacks = mutableListOf<String>()
    private val receivedQueueTypes = mutableListOf<String>()

    @BeforeTest
    override fun setup() {
        super.setup()
        receivedCallbacks.clear()
        receivedQueueTypes.clear()

        handler = WebSocketMessageHandler(
            onStreamMessage = { receivedCallbacks.add("stream") },
            onCompleteMessage = { receivedCallbacks.add("complete") },
            onErrorMessage = { error -> receivedCallbacks.add("error:$error") },
            onStatusMessage = { receivedCallbacks.add("status") },
            onPongMessage = { receivedCallbacks.add("pong") },
            onQueueMessage = { type ->
                receivedCallbacks.add("queue")
                receivedQueueTypes.add(type)
            }
        )
    }

    @AfterTest
    override fun tearDown() {
        super.tearDown()
    }

    // ========== Stream Messages ==========

    @Test
    fun `stream message invokes onStreamMessage callback`() = runTest {
        val message = IncomingMessage(type = MessageType.STREAM, content = "test content")

        handler.handleMessage(message)

        assertEquals(listOf("stream"), receivedCallbacks)
    }

    @Test
    fun `stream message with empty content still invokes callback`() = runTest {
        val message = IncomingMessage(type = MessageType.STREAM, content = "")

        handler.handleMessage(message)

        assertEquals(listOf("stream"), receivedCallbacks)
    }

    // ========== Complete Messages ==========

    @Test
    fun `complete message invokes onCompleteMessage callback`() = runTest {
        val message = IncomingMessage(type = MessageType.COMPLETE)

        handler.handleMessage(message)

        assertEquals(listOf("complete"), receivedCallbacks)
    }

    // ========== Error Messages ==========

    @Test
    fun `error message invokes onErrorMessage callback with error text`() = runTest {
        val message = IncomingMessage(type = MessageType.ERROR, error = "Something went wrong")

        handler.handleMessage(message)

        assertEquals(listOf("error:Something went wrong"), receivedCallbacks)
    }

    @Test
    fun `error message with null error uses default message`() = runTest {
        val message = IncomingMessage(type = MessageType.ERROR, error = null)

        handler.handleMessage(message)

        assertEquals(listOf("error:Unknown error"), receivedCallbacks)
    }

    // ========== Status Messages ==========

    @Test
    fun `status message invokes onStatusMessage callback`() = runTest {
        val message = IncomingMessage(type = MessageType.STATUS)

        handler.handleMessage(message)

        assertEquals(listOf("status"), receivedCallbacks)
    }

    // ========== Pong Messages ==========

    @Test
    fun `pong message invokes onPongMessage callback`() = runTest {
        val message = IncomingMessage(type = MessageType.PONG)

        handler.handleMessage(message)

        assertEquals(listOf("pong"), receivedCallbacks)
    }

    // ========== Queue Messages ==========

    @Test
    fun `queue add message invokes onQueueMessage callback`() = runTest {
        val message = IncomingMessage(type = MessageType.QUEUE_ADD)

        handler.handleMessage(message)

        assertEquals(listOf("queue"), receivedCallbacks)
        assertEquals(listOf(MessageType.QUEUE_ADD), receivedQueueTypes)
    }

    @Test
    fun `queue remove message invokes onQueueMessage callback`() = runTest {
        val message = IncomingMessage(type = MessageType.QUEUE_REMOVE)

        handler.handleMessage(message)

        assertEquals(listOf("queue"), receivedCallbacks)
        assertEquals(listOf(MessageType.QUEUE_REMOVE), receivedQueueTypes)
    }

    @Test
    fun `queue sync message invokes onQueueMessage callback`() = runTest {
        val message = IncomingMessage(type = MessageType.QUEUE_SYNC)

        handler.handleMessage(message)

        assertEquals(listOf("queue"), receivedCallbacks)
        assertEquals(listOf(MessageType.QUEUE_SYNC), receivedQueueTypes)
    }

    // ========== Multiple Messages ==========

    @Test
    fun `multiple messages invoke callbacks in order`() = runTest {
        handler.handleMessage(IncomingMessage(type = MessageType.STREAM, content = "chunk1"))
        handler.handleMessage(IncomingMessage(type = MessageType.STREAM, content = "chunk2"))
        handler.handleMessage(IncomingMessage(type = MessageType.COMPLETE))

        assertEquals(listOf("stream", "stream", "complete"), receivedCallbacks)
    }

    // ========== Message Type Classification ==========

    @Test
    fun `isStreamingMessage returns true for stream type`() {
        val message = IncomingMessage(type = MessageType.STREAM, content = "test")

        assertTrue(handler.isStreamingMessage(message))
    }

    @Test
    fun `isStreamingMessage returns false for complete type`() {
        val message = IncomingMessage(type = MessageType.COMPLETE)

        assertTrue(!handler.isStreamingMessage(message))
    }

    @Test
    fun `isQueueMessage returns true for queue types`() {
        assertTrue(handler.isQueueMessage(IncomingMessage(type = MessageType.QUEUE_ADD)))
        assertTrue(handler.isQueueMessage(IncomingMessage(type = MessageType.QUEUE_REMOVE)))
        assertTrue(handler.isQueueMessage(IncomingMessage(type = MessageType.QUEUE_SYNC)))
    }

    @Test
    fun `isQueueMessage returns false for non-queue types`() {
        assertTrue(!handler.isQueueMessage(IncomingMessage(type = MessageType.STREAM)))
        assertTrue(!handler.isQueueMessage(IncomingMessage(type = MessageType.COMPLETE)))
        assertTrue(!handler.isQueueMessage(IncomingMessage(type = MessageType.ERROR)))
    }

    @Test
    fun `isTerminalMessage returns true for complete and error`() {
        assertTrue(handler.isTerminalMessage(IncomingMessage(type = MessageType.COMPLETE)))
        assertTrue(handler.isTerminalMessage(IncomingMessage(type = MessageType.ERROR)))
    }

    @Test
    fun `isTerminalMessage returns false for stream and status`() {
        assertTrue(!handler.isTerminalMessage(IncomingMessage(type = MessageType.STREAM)))
        assertTrue(!handler.isTerminalMessage(IncomingMessage(type = MessageType.STATUS)))
    }

    // ========== Callback Not Set ==========

    @Test
    fun `handler works with minimal callbacks`() = runTest {
        val minimalHandler = WebSocketMessageHandler(
            onStreamMessage = { receivedCallbacks.add("stream") },
            onCompleteMessage = { receivedCallbacks.add("complete") },
            onErrorMessage = { receivedCallbacks.add("error") }
        )

        // These should not throw even without all callbacks
        minimalHandler.handleMessage(IncomingMessage(type = MessageType.STATUS))
        minimalHandler.handleMessage(IncomingMessage(type = MessageType.PONG))
        minimalHandler.handleMessage(IncomingMessage(type = MessageType.QUEUE_ADD))

        assertTrue(receivedCallbacks.isEmpty())
    }

    // ========== getErrorMessage ==========

    @Test
    fun `getErrorMessage returns error for error message`() {
        val message = IncomingMessage(type = MessageType.ERROR, error = "Test error")

        assertEquals("Test error", handler.getErrorMessage(message))
    }

    @Test
    fun `getErrorMessage returns default for error message without error field`() {
        val message = IncomingMessage(type = MessageType.ERROR)

        assertEquals("Unknown error", handler.getErrorMessage(message))
    }

    @Test
    fun `getErrorMessage returns null for non-error message`() {
        val message = IncomingMessage(type = MessageType.STREAM)

        assertEquals(null, handler.getErrorMessage(message))
    }
}
