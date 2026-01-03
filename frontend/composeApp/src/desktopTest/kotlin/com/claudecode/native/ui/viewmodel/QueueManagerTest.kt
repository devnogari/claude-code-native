package com.claudecode.native.ui.viewmodel

import app.cash.turbine.test
import com.claudecode.native.ViewModelTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerTest : ViewModelTestBase() {

    private lateinit var manager: QueueManager

    @BeforeTest
    fun setUp() {
        super.setup()
        manager = QueueManager()
    }

    @AfterTest
    fun cleanUp() {
        super.tearDown()
    }

    // ===== Initial State Tests =====

    @Test
    fun `initial queue should be empty for any conversation`() = runTest {
        manager.getQueue("conv1").test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `initial queue should be empty for different conversations`() = runTest {
        manager.getQueue("conv1").test {
            assertTrue(awaitItem().isEmpty())
        }
        manager.getQueue("conv2").test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    // ===== Add Message Tests =====

    @Test
    fun `addToQueue should add message to conversation queue`() = runTest {
        val message = QueuedMessage(
            id = "msg1",
            content = "Hello",
            queuedAt = 1000L,
            source = QueuedMessageSource.LOCAL
        )

        manager.addToQueue("conv1", message)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(1, queue.size)
            assertEquals("msg1", queue[0].id)
            assertEquals("Hello", queue[0].content)
        }
    }

    @Test
    fun `addToQueue should append multiple messages in order`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        val msg2 = QueuedMessage("msg2", "Second", 2000L, QueuedMessageSource.SERVER)

        manager.addToQueue("conv1", msg1)
        manager.addToQueue("conv1", msg2)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(2, queue.size)
            assertEquals("msg1", queue[0].id)
            assertEquals("msg2", queue[1].id)
        }
    }

    // ===== Remove Message Tests =====

    @Test
    fun `removeFromQueue should remove message by ID`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        val msg2 = QueuedMessage("msg2", "Second", 2000L, QueuedMessageSource.SERVER)

        manager.addToQueue("conv1", msg1)
        manager.addToQueue("conv1", msg2)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.removeFromQueue("conv1", "msg1")
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(1, queue.size)
            assertEquals("msg2", queue[0].id)
        }
    }

    @Test
    fun `removeFromQueue should do nothing for non-existent id`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        manager.addToQueue("conv1", msg1)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.removeFromQueue("conv1", "non-existent")
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(1, queue.size)
            assertEquals("msg1", queue[0].id)
        }
    }

    // ===== Remove Messages Before Timestamp Tests =====

    @Test
    fun `removeMessagesBefore should remove messages before timestamp`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        val msg2 = QueuedMessage("msg2", "Second", 2000L, QueuedMessageSource.SERVER)
        val msg3 = QueuedMessage("msg3", "Third", 3000L, QueuedMessageSource.CLI)

        manager.addToQueue("conv1", msg1)
        manager.addToQueue("conv1", msg2)
        manager.addToQueue("conv1", msg3)
        testDispatcher.scheduler.advanceUntilIdle()

        // Remove messages before timestamp 2500 (should keep msg3 only)
        manager.removeMessagesBefore("conv1", 2500L)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(1, queue.size)
            assertEquals("msg3", queue[0].id)
        }
    }

    @Test
    fun `removeMessagesBefore should keep messages at or after timestamp`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        val msg2 = QueuedMessage("msg2", "Second", 2000L, QueuedMessageSource.SERVER)

        manager.addToQueue("conv1", msg1)
        manager.addToQueue("conv1", msg2)
        testDispatcher.scheduler.advanceUntilIdle()

        // Remove messages before timestamp 2000 (exact match should be kept)
        manager.removeMessagesBefore("conv1", 2000L)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(1, queue.size)
            assertEquals("msg2", queue[0].id)
        }
    }

    // ===== Clear Queue Tests =====

    @Test
    fun `clearQueue should empty conversation queue`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        val msg2 = QueuedMessage("msg2", "Second", 2000L, QueuedMessageSource.SERVER)

        manager.addToQueue("conv1", msg1)
        manager.addToQueue("conv1", msg2)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.clearQueue("conv1")
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `clearQueue should not affect other conversations`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        val msg2 = QueuedMessage("msg2", "Second", 2000L, QueuedMessageSource.SERVER)

        manager.addToQueue("conv1", msg1)
        manager.addToQueue("conv2", msg2)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.clearQueue("conv1")
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            assertTrue(awaitItem().isEmpty())
        }
        manager.getQueue("conv2").test {
            val queue = awaitItem()
            assertEquals(1, queue.size)
            assertEquals("msg2", queue[0].id)
        }
    }

    // ===== Update Queue Tests =====

    @Test
    fun `updateQueue should replace entire queue`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        manager.addToQueue("conv1", msg1)
        testDispatcher.scheduler.advanceUntilIdle()

        val newQueue = listOf(
            QueuedMessage("msg2", "New1", 2000L, QueuedMessageSource.SERVER),
            QueuedMessage("msg3", "New2", 3000L, QueuedMessageSource.SERVER)
        )
        manager.updateQueue("conv1", newQueue)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(2, queue.size)
            assertEquals("msg2", queue[0].id)
            assertEquals("msg3", queue[1].id)
        }
    }

    @Test
    fun `updateQueue with empty list should clear queue`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        manager.addToQueue("conv1", msg1)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.updateQueue("conv1", emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    // ===== Per-Conversation Isolation Tests =====

    @Test
    fun `queues should be isolated per conversation - add to conv1, conv2 should be empty`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        manager.addToQueue("conv1", msg1)
        testDispatcher.scheduler.advanceUntilIdle()

        // conv2 should still be empty
        manager.getQueue("conv2").test {
            assertTrue(awaitItem().isEmpty())
        }

        // conv1 should have the message
        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(1, queue.size)
            assertEquals("msg1", queue[0].id)
        }
    }

    @Test
    fun `queues should be isolated per conversation - separate queues`() = runTest {
        val msg1 = QueuedMessage("msg1", "Conv1 Message", 1000L, QueuedMessageSource.LOCAL)
        val msg2 = QueuedMessage("msg2", "Conv2 Message", 2000L, QueuedMessageSource.SERVER)
        val msg3 = QueuedMessage("msg3", "Conv1 Another", 3000L, QueuedMessageSource.CLI)

        manager.addToQueue("conv1", msg1)
        manager.addToQueue("conv2", msg2)
        manager.addToQueue("conv1", msg3)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(2, queue.size)
            assertEquals("msg1", queue[0].id)
            assertEquals("msg3", queue[1].id)
        }

        manager.getQueue("conv2").test {
            val queue = awaitItem()
            assertEquals(1, queue.size)
            assertEquals("msg2", queue[0].id)
        }
    }

    // ===== Queue Size Tests =====

    @Test
    fun `getQueueSize should return correct count`() = runTest {
        assertEquals(0, manager.getQueueSize("conv1"))

        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        val msg2 = QueuedMessage("msg2", "Second", 2000L, QueuedMessageSource.SERVER)

        manager.addToQueue("conv1", msg1)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, manager.getQueueSize("conv1"))

        manager.addToQueue("conv1", msg2)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, manager.getQueueSize("conv1"))

        manager.removeFromQueue("conv1", "msg1")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, manager.getQueueSize("conv1"))
    }

    @Test
    fun `getQueueSize should return zero for non-existent conversation`() {
        assertEquals(0, manager.getQueueSize("non-existent"))
    }

    // ===== Has Local Messages Tests =====

    @Test
    fun `hasLocalMessages should return false for empty queue`() {
        assertFalse(manager.hasLocalMessages("conv1"))
    }

    @Test
    fun `hasLocalMessages should detect LOCAL source messages`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        manager.addToQueue("conv1", msg1)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(manager.hasLocalMessages("conv1"))
    }

    @Test
    fun `hasLocalMessages should return false for SERVER only messages`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.SERVER)
        manager.addToQueue("conv1", msg1)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(manager.hasLocalMessages("conv1"))
    }

    @Test
    fun `hasLocalMessages should return false for CLI only messages`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.CLI)
        manager.addToQueue("conv1", msg1)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(manager.hasLocalMessages("conv1"))
    }

    @Test
    fun `hasLocalMessages should return true if at least one LOCAL message exists`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.SERVER)
        val msg2 = QueuedMessage("msg2", "Second", 2000L, QueuedMessageSource.LOCAL)
        val msg3 = QueuedMessage("msg3", "Third", 3000L, QueuedMessageSource.CLI)

        manager.addToQueue("conv1", msg1)
        manager.addToQueue("conv1", msg2)
        manager.addToQueue("conv1", msg3)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(manager.hasLocalMessages("conv1"))
    }

    // ===== Max Queue Size Tests =====

    @Test
    fun `queue should respect maxQueueSize limit`() = runTest {
        val maxSize = 10

        // Add more than max messages
        repeat(15) { i ->
            val msg = QueuedMessage("msg$i", "Content $i", (1000 + i).toLong(), QueuedMessageSource.LOCAL)
            manager.addToQueue("conv1", msg)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            // Should be capped at maxQueueSize
            assertEquals(maxSize, queue.size)
        }
    }

    @Test
    fun `isQueueFull should return true when at max capacity`() = runTest {
        val maxSize = 10

        // Add exactly max messages
        repeat(maxSize) { i ->
            val msg = QueuedMessage("msg$i", "Content $i", (1000 + i).toLong(), QueuedMessageSource.LOCAL)
            manager.addToQueue("conv1", msg)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(manager.isQueueFull("conv1"))
    }

    @Test
    fun `isQueueFull should return false when below max capacity`() = runTest {
        val msg = QueuedMessage("msg1", "Content", 1000L, QueuedMessageSource.LOCAL)
        manager.addToQueue("conv1", msg)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(manager.isQueueFull("conv1"))
    }

    @Test
    fun `isQueueFull should return false for empty queue`() {
        assertFalse(manager.isQueueFull("conv1"))
    }

    // ===== Transform Queue Tests =====

    @Test
    fun `transformQueue should apply transformation function`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        val msg2 = QueuedMessage("msg2", "Second", 2000L, QueuedMessageSource.SERVER)

        manager.addToQueue("conv1", msg1)
        manager.addToQueue("conv1", msg2)
        testDispatcher.scheduler.advanceUntilIdle()

        // Remove first message using transform
        manager.transformQueue("conv1") { queue ->
            queue.drop(1)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(1, queue.size)
            assertEquals("msg2", queue[0].id)
        }
    }

    @Test
    fun `transformQueue should work on empty queue`() = runTest {
        manager.transformQueue("conv1") { queue ->
            queue + QueuedMessage("msg1", "Added", 1000L, QueuedMessageSource.LOCAL)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(1, queue.size)
            assertEquals("msg1", queue[0].id)
        }
    }

    // ===== Get Local Messages Tests =====

    @Test
    fun `getLocalMessages should return only LOCAL source messages`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.LOCAL)
        val msg2 = QueuedMessage("msg2", "Second", 2000L, QueuedMessageSource.SERVER)
        val msg3 = QueuedMessage("msg3", "Third", 3000L, QueuedMessageSource.LOCAL)
        val msg4 = QueuedMessage("msg4", "Fourth", 4000L, QueuedMessageSource.CLI)

        manager.addToQueue("conv1", msg1)
        manager.addToQueue("conv1", msg2)
        manager.addToQueue("conv1", msg3)
        manager.addToQueue("conv1", msg4)
        testDispatcher.scheduler.advanceUntilIdle()

        val localMessages = manager.getLocalMessages("conv1")
        assertEquals(2, localMessages.size)
        assertEquals("msg1", localMessages[0].id)
        assertEquals("msg3", localMessages[1].id)
    }

    @Test
    fun `getLocalMessages should return empty list when no local messages`() = runTest {
        val msg1 = QueuedMessage("msg1", "First", 1000L, QueuedMessageSource.SERVER)
        manager.addToQueue("conv1", msg1)
        testDispatcher.scheduler.advanceUntilIdle()

        val localMessages = manager.getLocalMessages("conv1")
        assertTrue(localMessages.isEmpty())
    }

    // ===== Thread Safety Tests =====

    @Test
    fun `concurrent addToQueue calls should be thread-safe`() = runTest {
        repeat(10) { i ->
            val msg = QueuedMessage("msg$i", "Content $i", (1000 + i).toLong(), QueuedMessageSource.LOCAL)
            manager.addToQueue("conv1", msg)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        manager.getQueue("conv1").test {
            val queue = awaitItem()
            assertEquals(10, queue.size)
        }
    }
}
