package com.claudecode.native.ui.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for QueueManager constants and static behaviors.
 *
 * Note: Full integration testing of QueueManager is done through ChatViewModelTest
 * as QueueManager requires many dependencies (WebSocket, API, CoroutineScope).
 * These tests focus on testable static behaviors without mocking the full dependency graph.
 */
class QueueManagerTest {

    @Test
    fun `MAX_QUEUED_MESSAGES is 10`() {
        assertEquals(10, QueueManager.MAX_QUEUED_MESSAGES)
    }

    @Test
    fun `QueuedMessage data class properties`() {
        val message = QueuedMessage(
            id = "test-id",
            content = "Test content",
            queuedAt = 1234567890L,
            source = QueuedMessageSource.LOCAL
        )

        assertEquals("test-id", message.id)
        assertEquals("Test content", message.content)
        assertEquals(QueuedMessageSource.LOCAL, message.source)
        assertEquals(1234567890L, message.queuedAt)
        assertTrue(message.images.isEmpty())
        assertTrue(message.serverImages.isEmpty())
    }

    @Test
    fun `QueuedMessage sources enum values exist`() {
        // Verify all expected source types are available
        assertEquals("LOCAL", QueuedMessageSource.LOCAL.name)
        assertEquals("CLI", QueuedMessageSource.CLI.name)
        assertEquals("SERVER", QueuedMessageSource.SERVER.name)
    }

    @Test
    fun `QueuedMessage default source is LOCAL`() {
        val message = QueuedMessage(
            id = "test-id",
            content = "Test content",
            queuedAt = 0L
        )
        assertEquals(QueuedMessageSource.LOCAL, message.source)
    }
}
