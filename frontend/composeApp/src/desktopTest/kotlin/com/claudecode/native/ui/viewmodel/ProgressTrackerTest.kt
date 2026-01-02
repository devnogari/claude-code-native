package com.claudecode.native.ui.viewmodel

import app.cash.turbine.test
import com.claudecode.native.ViewModelTestBase
import com.claudecode.native.data.model.TodoItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comprehensive tests for ProgressTracker.
 * Tests progress tracking, todos, and per-conversation isolation.
 *
 * Note: Each test cleans up within the runTest block to ensure
 * elapsed time coroutines don't block test completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressTrackerTest : ViewModelTestBase() {

    private lateinit var tracker: ProgressTracker

    @BeforeTest
    override fun setup() {
        super.setup()
        tracker = ProgressTracker()
    }

    @AfterTest
    override fun tearDown() {
        // Additional cleanup if needed
        super.tearDown()
    }

    // Helper to run tests with cleanup
    private fun runTestWithCleanup(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            tracker.cleanup()
        }
    }

    // ========== Basic Operations ==========

    @Test
    fun `initial state is inactive`() = runTestWithCleanup {
        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val initial = awaitItem()
            assertFalse(initial.isActive)
            assertEquals("", initial.statusText)
            assertEquals(0, initial.elapsedSeconds)
            assertTrue(initial.todos.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `start progress tracking makes it active`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertTrue(current.isActive)
            assertEquals("Processing", current.statusText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stop progress tracking makes it inactive`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)
        tracker.stopProgressTracking("conv-1")

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertFalse(current.isActive)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `start with custom status text`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Running tests", this)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertEquals("Running tests", current.statusText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `start with empty status text`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "", this)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertTrue(current.isActive)
            assertEquals("", current.statusText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Elapsed Time Tracking ==========

    @Test
    fun `start resets elapsed time to zero`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertEquals(0, current.elapsedSeconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Todo Management ==========

    @Test
    fun `update todos on active progress`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)

        val todos = listOf(
            TodoItem(content = "Run tests", status = "in_progress", activeForm = "Running tests"),
            TodoItem(content = "Fix bugs", status = "pending", activeForm = "Fixing bugs")
        )
        tracker.updateTodos("conv-1", todos)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertEquals(2, current.todos.size)
            assertEquals("Run tests", current.todos[0].content)
            assertEquals("in_progress", current.todos[0].status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `todos preserved when stopping progress`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)

        val todos = listOf(
            TodoItem(content = "Task 1", status = "completed", activeForm = "Doing Task 1")
        )
        tracker.updateTodos("conv-1", todos)
        tracker.stopProgressTracking("conv-1")

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertFalse(current.isActive)
            assertEquals(1, current.todos.size)
            assertEquals("Task 1", current.todos[0].content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `todos preserved when starting progress`() = runTestWithCleanup {
        // Set initial todos
        tracker.startProgressTracking("conv-1", "Initial", this)
        val todos = listOf(
            TodoItem(content = "Existing task", status = "pending", activeForm = "Doing task")
        )
        tracker.updateTodos("conv-1", todos)
        tracker.stopProgressTracking("conv-1")

        // Restart progress - todos should be preserved
        tracker.startProgressTracking("conv-1", "Resumed", this)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertTrue(current.isActive)
            assertEquals(1, current.todos.size)
            assertEquals("Existing task", current.todos[0].content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update todos only when changed returns false for same todos`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)

        val todos = listOf(
            TodoItem(content = "Task", status = "pending", activeForm = "Doing task")
        )

        // First update should return true
        val firstResult = tracker.updateTodos("conv-1", todos)
        assertTrue(firstResult)

        // Same todos should return false
        val secondResult = tracker.updateTodos("conv-1", todos)
        assertFalse(secondResult)
    }

    @Test
    fun `update todos on inactive conversation creates entry`() = runTestWithCleanup {
        // No active progress tracking
        val todos = listOf(
            TodoItem(content = "Task", status = "pending", activeForm = "Doing task")
        )

        // Should still work - creates the progress entry
        tracker.updateTodos("conv-1", todos)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertEquals(1, current.todos.size)
            assertFalse(current.isActive) // Still inactive
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Per-Conversation Isolation ==========

    @Test
    fun `conversations have independent progress status`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing 1", this)
        tracker.startProgressTracking("conv-2", "Processing 2", this)

        val status1 = tracker.getProgressStatus("conv-1")
        val status2 = tracker.getProgressStatus("conv-2")

        assertEquals("Processing 1", status1.value.statusText)
        assertEquals("Processing 2", status2.value.statusText)
    }

    @Test
    fun `stopping one conversation does not affect others`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing 1", this)
        tracker.startProgressTracking("conv-2", "Processing 2", this)

        tracker.stopProgressTracking("conv-1")

        assertFalse(tracker.getProgressStatus("conv-1").value.isActive)
        assertTrue(tracker.getProgressStatus("conv-2").value.isActive)
    }

    @Test
    fun `conversations have independent todos`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)
        tracker.startProgressTracking("conv-2", "Processing", this)

        val todos1 = listOf(TodoItem("Task A", "pending", "Doing A"))
        val todos2 = listOf(TodoItem("Task B", "in_progress", "Doing B"))

        tracker.updateTodos("conv-1", todos1)
        tracker.updateTodos("conv-2", todos2)

        assertEquals("Task A", tracker.getProgressStatus("conv-1").value.todos[0].content)
        assertEquals("Task B", tracker.getProgressStatus("conv-2").value.todos[0].content)
    }

    // ========== Clear Operations ==========

    @Test
    fun `clear removes all state for conversation`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)
        val todos = listOf(TodoItem("Task", "pending", "Doing task"))
        tracker.updateTodos("conv-1", todos)

        tracker.clearProgressState("conv-1")

        // Should return fresh inactive status
        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertFalse(current.isActive)
            assertTrue(current.todos.isEmpty())
            assertEquals(0, current.elapsedSeconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clear one conversation does not affect others`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing 1", this)
        tracker.startProgressTracking("conv-2", "Processing 2", this)

        val todos = listOf(TodoItem("Task", "pending", "Doing task"))
        tracker.updateTodos("conv-2", todos)

        tracker.clearProgressState("conv-1")

        // conv-2 should be unaffected
        assertTrue(tracker.getProgressStatus("conv-2").value.isActive)
        assertEquals(1, tracker.getProgressStatus("conv-2").value.todos.size)
    }

    @Test
    fun `clear non-existent conversation is safe`() = runTestWithCleanup {
        // Should not throw
        tracker.clearProgressState("non-existent")
    }

    // ========== Cleanup ==========

    @Test
    fun `cleanup clears all state`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)
        tracker.startProgressTracking("conv-2", "Processing", this)

        tracker.cleanup()

        // All progress should be cleared
        assertFalse(tracker.getProgressStatus("conv-1").value.isActive)
        assertFalse(tracker.getProgressStatus("conv-2").value.isActive)
    }

    // ========== Is Active Checks ==========

    @Test
    fun `isActive returns true for active conversation`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)

        assertTrue(tracker.isActive("conv-1"))
    }

    @Test
    fun `isActive returns false for stopped conversation`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)
        tracker.stopProgressTracking("conv-1")

        assertFalse(tracker.isActive("conv-1"))
    }

    @Test
    fun `isActive returns false for unknown conversation`() = runTestWithCleanup {
        assertFalse(tracker.isActive("unknown"))
    }

    // ========== Edge Cases ==========

    @Test
    fun `stop on non-existent conversation is safe`() = runTestWithCleanup {
        // Should not throw
        tracker.stopProgressTracking("non-existent")
    }

    @Test
    fun `multiple starts reset state correctly`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "First", this)
        tracker.startProgressTracking("conv-1", "Second", this)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertEquals("Second", current.statusText)
            assertEquals(0, current.elapsedSeconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `can restart after clear`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Initial", this)
        tracker.clearProgressState("conv-1")
        tracker.startProgressTracking("conv-1", "Restarted", this)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertTrue(current.isActive)
            assertEquals("Restarted", current.statusText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Token and Thinking Time ==========

    @Test
    fun `update token count`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Processing", this)
        tracker.updateTokens("conv-1", 1500)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertEquals(1500, current.tokenCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update thinking time`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Thinking", this)
        tracker.updateThinkingTime("conv-1", 10)

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertEquals(10, current.thinkingSeconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update status text`() = runTestWithCleanup {
        tracker.startProgressTracking("conv-1", "Initial", this)
        tracker.updateStatusText("conv-1", "Running tests")

        val status = tracker.getProgressStatus("conv-1")
        status.test {
            val current = awaitItem()
            assertEquals("Running tests", current.statusText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update on non-existent conversation is safe`() = runTestWithCleanup {
        // These should not throw
        tracker.updateTokens("non-existent", 100)
        tracker.updateThinkingTime("non-existent", 5)
        tracker.updateStatusText("non-existent", "Status")
    }
}
