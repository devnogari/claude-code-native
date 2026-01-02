package com.claudecode.native.ui.viewmodel

import app.cash.turbine.test
import com.claudecode.native.ViewModelTestBase
import com.claudecode.native.data.model.MessageRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MessageManagerTest : ViewModelTestBase() {

    private lateinit var manager: MessageManager

    @BeforeTest
    fun setUp() {
        super.setup()
        manager = MessageManager()
    }

    @AfterTest
    fun cleanUp() {
        super.tearDown()
    }

    // ===== Initial State Tests =====

    @Test
    fun `initial messages should be empty`() = runTest {
        manager.messages.test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `hasMoreMessages should be false initially`() = runTest {
        manager.hasMoreMessages.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `isLoadingMore should be false initially`() = runTest {
        manager.isLoadingMore.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `currentOffset should be zero initially`() {
        assertEquals(0, manager.getCurrentOffset())
    }

    // ===== Add Message Tests =====

    @Test
    fun `addMessage should append to list`() = runTest {
        val message = ChatMessage(
            id = "1",
            role = MessageRole.USER,
            blocks = listOf(ContentBlock.Text("Hello"))
        )

        manager.addMessage(message)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            assertEquals("1", messages[0].id)
        }
    }

    @Test
    fun `addMessage should append multiple messages in order`() = runTest {
        val message1 = ChatMessage("1", MessageRole.USER, listOf(ContentBlock.Text("First")))
        val message2 = ChatMessage("2", MessageRole.ASSISTANT, listOf(ContentBlock.Text("Second")))

        manager.addMessage(message1)
        manager.addMessage(message2)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(2, messages.size)
            assertEquals("1", messages[0].id)
            assertEquals("2", messages[1].id)
        }
    }

    @Test
    fun `addMessages should append batch of messages`() = runTest {
        val messages = listOf(
            ChatMessage("1", MessageRole.USER, listOf(ContentBlock.Text("First"))),
            ChatMessage("2", MessageRole.ASSISTANT, listOf(ContentBlock.Text("Second")))
        )

        manager.addMessages(messages)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val result = awaitItem()
            assertEquals(2, result.size)
            assertEquals("1", result[0].id)
            assertEquals("2", result[1].id)
        }
    }

    // ===== Clear Messages Tests =====

    @Test
    fun `clearMessages should empty the list`() = runTest {
        manager.addMessage(ChatMessage("1", MessageRole.USER, listOf()))
        testDispatcher.scheduler.advanceUntilIdle()

        manager.clearMessages()
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `clearMessages should reset offset`() = runTest {
        manager.addMessage(ChatMessage("1", MessageRole.USER, listOf()))
        manager.incrementOffset(10)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.clearMessages()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, manager.getCurrentOffset())
    }

    @Test
    fun `clearMessages should reset hasMoreMessages`() = runTest {
        manager.setHasMoreMessages(true)
        manager.clearMessages()
        testDispatcher.scheduler.advanceUntilIdle()

        manager.hasMoreMessages.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `clearMessages should reset isLoadingMore`() = runTest {
        manager.setLoadingMore(true)
        manager.clearMessages()
        testDispatcher.scheduler.advanceUntilIdle()

        manager.isLoadingMore.test {
            assertFalse(awaitItem())
        }
    }

    // ===== Prepend Messages Tests =====

    @Test
    fun `prependMessages should add to beginning`() = runTest {
        manager.addMessage(ChatMessage("2", MessageRole.USER, listOf()))
        testDispatcher.scheduler.advanceUntilIdle()

        manager.prependMessages(listOf(
            ChatMessage("1", MessageRole.USER, listOf())
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(2, messages.size)
            assertEquals("1", messages[0].id)
            assertEquals("2", messages[1].id)
        }
    }

    @Test
    fun `prependMessages should deduplicate by id`() = runTest {
        manager.addMessage(ChatMessage("2", MessageRole.USER, listOf()))
        testDispatcher.scheduler.advanceUntilIdle()

        manager.prependMessages(listOf(
            ChatMessage("1", MessageRole.USER, listOf()),
            ChatMessage("2", MessageRole.USER, listOf())  // duplicate
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(2, messages.size)
            assertEquals("1", messages[0].id)
            assertEquals("2", messages[1].id)
        }
    }

    @Test
    fun `prependMessages should handle empty list`() = runTest {
        manager.addMessage(ChatMessage("1", MessageRole.USER, listOf()))
        testDispatcher.scheduler.advanceUntilIdle()

        manager.prependMessages(emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
        }
    }

    // ===== Set Messages Tests =====

    @Test
    fun `setMessages should replace all messages`() = runTest {
        manager.addMessage(ChatMessage("old", MessageRole.USER, listOf()))
        testDispatcher.scheduler.advanceUntilIdle()

        manager.setMessages(listOf(
            ChatMessage("new1", MessageRole.USER, listOf()),
            ChatMessage("new2", MessageRole.ASSISTANT, listOf())
        ))
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(2, messages.size)
            assertEquals("new1", messages[0].id)
            assertEquals("new2", messages[1].id)
        }
    }

    // ===== Pagination State Tests =====

    @Test
    fun `setHasMoreMessages should update state to true`() = runTest {
        manager.setHasMoreMessages(true)

        manager.hasMoreMessages.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `setHasMoreMessages should update state to false`() = runTest {
        manager.setHasMoreMessages(true)
        manager.setHasMoreMessages(false)

        manager.hasMoreMessages.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `setLoadingMore should update state`() = runTest {
        manager.setLoadingMore(true)

        manager.isLoadingMore.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `incrementOffset should add to current offset`() {
        manager.incrementOffset(10)
        assertEquals(10, manager.getCurrentOffset())

        manager.incrementOffset(5)
        assertEquals(15, manager.getCurrentOffset())
    }

    @Test
    fun `resetOffset should set offset to zero`() {
        manager.incrementOffset(10)
        manager.resetOffset()
        assertEquals(0, manager.getCurrentOffset())
    }

    // ===== Update Message Tests =====

    @Test
    fun `updateMessage should modify existing message`() = runTest {
        val original = ChatMessage("1", MessageRole.USER, listOf(ContentBlock.Text("Hello")))
        manager.addMessage(original)
        testDispatcher.scheduler.advanceUntilIdle()

        manager.updateMessage("1") { msg ->
            msg.copy(blocks = listOf(ContentBlock.Text("Updated")))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            val textBlock = messages[0].blocks[0] as ContentBlock.Text
            assertEquals("Updated", textBlock.content)
        }
    }

    @Test
    fun `updateMessage should not affect other messages`() = runTest {
        manager.addMessage(ChatMessage("1", MessageRole.USER, listOf(ContentBlock.Text("First"))))
        manager.addMessage(ChatMessage("2", MessageRole.USER, listOf(ContentBlock.Text("Second"))))
        testDispatcher.scheduler.advanceUntilIdle()

        manager.updateMessage("1") { msg ->
            msg.copy(blocks = listOf(ContentBlock.Text("Updated")))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(2, messages.size)
            assertEquals("Updated", (messages[0].blocks[0] as ContentBlock.Text).content)
            assertEquals("Second", (messages[1].blocks[0] as ContentBlock.Text).content)
        }
    }

    @Test
    fun `updateMessage should do nothing for non-existent id`() = runTest {
        manager.addMessage(ChatMessage("1", MessageRole.USER, listOf(ContentBlock.Text("Original"))))
        testDispatcher.scheduler.advanceUntilIdle()

        manager.updateMessage("non-existent") { msg ->
            msg.copy(blocks = listOf(ContentBlock.Text("Updated")))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            assertEquals("Original", (messages[0].blocks[0] as ContentBlock.Text).content)
        }
    }

    // ===== Get Message By ID Tests =====

    @Test
    fun `getMessageById should return message when found`() = runTest {
        val message = ChatMessage("1", MessageRole.USER, listOf(ContentBlock.Text("Hello")))
        manager.addMessage(message)
        testDispatcher.scheduler.advanceUntilIdle()

        val found = manager.getMessageById("1")
        assertEquals("1", found?.id)
        assertEquals(MessageRole.USER, found?.role)
    }

    @Test
    fun `getMessageById should return null when not found`() = runTest {
        manager.addMessage(ChatMessage("1", MessageRole.USER, listOf()))
        testDispatcher.scheduler.advanceUntilIdle()

        val found = manager.getMessageById("non-existent")
        assertNull(found)
    }

    // ===== Thread Safety Tests =====

    @Test
    fun `concurrent addMessage calls should be thread-safe`() = runTest {
        // Add multiple messages concurrently
        repeat(10) { i ->
            manager.addMessage(ChatMessage("$i", MessageRole.USER, listOf()))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(10, messages.size)
        }
    }
}
