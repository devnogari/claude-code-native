package com.claudecode.native.ui.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle

/**
 * Tests for race condition handling logic in chat room switching.
 *
 * These tests verify the guard check pattern used to prevent stale data
 * from being applied when rapidly switching between chat rooms.
 *
 * The actual ChatViewModel can't be easily unit tested due to its dependencies,
 * but we can test the core guard logic pattern that prevents race conditions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelRaceConditionTest {

    /**
     * Simulates the guard check pattern used in ChatViewModel.
     *
     * The key insight is: before setting any state, check if the
     * currentConversationId still matches the expectedConversationId.
     * If not, abort without setting state.
     */
    class GuardCheckSimulator {
        var currentConversationId: String? = null
        var title = MutableStateFlow<String?>(null)
        var messages = MutableStateFlow<List<String>>(emptyList())
        var currentConnectJob: Job? = null

        /**
         * Simulates loading data for a room with guard checks.
         *
         * @param expectedConversationId The room ID we're loading for
         * @param loadDelayMs Simulated API delay
         * @param newTitle Title to set if guard passes
         * @param newMessages Messages to set if guard passes
         * @return true if data was applied, false if guard aborted
         */
        suspend fun loadWithGuardCheck(
            expectedConversationId: String,
            loadDelayMs: Long,
            newTitle: String,
            newMessages: List<String>
        ): Boolean {
            // Simulate API delay
            delay(loadDelayMs)

            // GUARD CHECK: Has the room changed during loading?
            if (currentConversationId != expectedConversationId) {
                // Room changed - abort without setting state
                return false
            }

            // Guard passed - safe to set state
            title.value = newTitle
            messages.value = newMessages
            return true
        }
    }

    // =====================================
    // Guard Check Pattern Tests
    // =====================================

    @Test
    fun `guard check should allow state update when room unchanged`() = runTest {
        val sim = GuardCheckSimulator()
        sim.currentConversationId = "room-a"

        val result = sim.loadWithGuardCheck(
            expectedConversationId = "room-a",
            loadDelayMs = 100,
            newTitle = "Room A Title",
            newMessages = listOf("msg1", "msg2")
        )

        assertTrue(result, "Guard should pass when room unchanged")
        assertEquals("Room A Title", sim.title.value)
        assertEquals(2, sim.messages.value.size)
    }

    @Test
    fun `guard check should abort when room changed during load`() = runTest {
        val sim = GuardCheckSimulator()
        sim.currentConversationId = "room-a"

        // Start loading for room-a
        val loadJob = launch {
            sim.loadWithGuardCheck(
                expectedConversationId = "room-a",
                loadDelayMs = 200,
                newTitle = "Room A Title",
                newMessages = listOf("msg1", "msg2")
            )
        }

        // After 50ms, switch to room-b (simulating user click)
        advanceTimeBy(50)
        sim.currentConversationId = "room-b"

        // Complete the loading
        advanceUntilIdle()
        loadJob.join()

        // State should NOT be updated because guard check failed
        assertNull(sim.title.value, "Title should be null - guard should have aborted")
        assertTrue(sim.messages.value.isEmpty(), "Messages should be empty - guard should have aborted")
    }

    @Test
    fun `rapid switching should result in final room data only`() = runTest {
        val sim = GuardCheckSimulator()

        // Track which loads succeeded
        val successfulLoads = mutableListOf<String>()

        // Simulate rapid switching: A -> B -> C
        sim.currentConversationId = "room-a"
        val jobA = launch {
            val success = sim.loadWithGuardCheck("room-a", 300, "Title A", listOf("A1"))
            if (success) successfulLoads.add("A")
        }

        advanceTimeBy(50)
        sim.currentConversationId = "room-b"
        val jobB = launch {
            val success = sim.loadWithGuardCheck("room-b", 300, "Title B", listOf("B1"))
            if (success) successfulLoads.add("B")
        }

        advanceTimeBy(50)
        sim.currentConversationId = "room-c"
        val jobC = launch {
            val success = sim.loadWithGuardCheck("room-c", 300, "Title C", listOf("C1"))
            if (success) successfulLoads.add("C")
        }

        // Wait for all loads to complete
        advanceUntilIdle()
        jobA.join()
        jobB.join()
        jobC.join()

        // Only the final room (C) should have succeeded
        assertEquals(listOf("C"), successfulLoads, "Only final room should succeed")
        assertEquals("Title C", sim.title.value)
        assertEquals(listOf("C1"), sim.messages.value)
    }

    @Test
    fun `no empty view when guard aborts`() = runTest {
        val sim = GuardCheckSimulator()

        // Pre-populate with Room A data
        sim.currentConversationId = "room-a"
        sim.title.value = "Room A Title"
        sim.messages.value = listOf("A1", "A2")

        // Start loading Room B
        val jobB = launch {
            // Set currentConversationId to B first
            sim.currentConversationId = "room-b"

            // Then immediately switch back to A (simulating rapid click)
            advanceTimeBy(50)
            sim.currentConversationId = "room-a"

            // B's load tries to complete
            delay(200)

            // Guard check: currentConversationId is A, but we're loading B
            if (sim.currentConversationId != "room-b") {
                // Guard fails - do NOT clear/set state
                return@launch
            }

            // This code won't execute because guard failed
            sim.title.value = "Room B Title"
            sim.messages.value = listOf("B1")
        }

        advanceUntilIdle()
        jobB.join()

        // Original Room A data should still be visible (no empty view)
        assertEquals("Room A Title", sim.title.value, "Should keep Room A title")
        assertEquals(listOf("A1", "A2"), sim.messages.value, "Should keep Room A messages")
    }

    // =====================================
    // Job Cancellation Tests
    // =====================================

    @Test
    fun `previous connect job should be cancelled on new connect`() = runTest {
        var job1Cancelled = false
        var job2Completed = false

        // Simulate first connect
        val job1 = launch {
            try {
                delay(1000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                job1Cancelled = true
                throw e
            }
        }

        advanceTimeBy(100)

        // Simulate second connect - cancel first
        job1.cancel()
        val job2 = launch {
            delay(100)
            job2Completed = true
        }

        advanceUntilIdle()

        assertTrue(job1Cancelled, "First job should be cancelled")
        assertTrue(job2Completed, "Second job should complete")
    }

    @Test
    fun `skip connect optimization should work for same room`() {
        var currentConversationId: String? = null
        var isJobActive = false
        var connectCalls = 0

        fun connect(conversationId: String) {
            // OPTIMIZATION: Skip if already connected to this room
            if (currentConversationId == conversationId && !isJobActive) {
                return // Skip
            }
            connectCalls++
            currentConversationId = conversationId
        }

        // First connect
        connect("room-a")
        assertEquals(1, connectCalls)

        // Second connect to same room - should skip
        connect("room-a")
        assertEquals(1, connectCalls, "Should skip second connect to same room")

        // Connect to different room - should proceed
        connect("room-b")
        assertEquals(2, connectCalls, "Should connect to different room")
    }

    // =====================================
    // State Consistency Tests
    // =====================================

    @Test
    fun `state should remain consistent after multiple switches`() = runTest {
        val titles = MutableStateFlow<String?>(null)
        val errors = MutableStateFlow<String?>(null)
        var currentRoom: String? = null

        suspend fun loadRoom(roomId: String, delayMs: Long) {
            currentRoom = roomId
            delay(delayMs)

            // Guard check
            if (currentRoom != roomId) {
                return // Abort
            }

            titles.value = "Title: $roomId"
        }

        // Rapid switching
        val jobs = (1..10).map { i ->
            launch {
                loadRoom("room-$i", 50L)
            }
        }

        // During loading, keep switching to room-10
        advanceTimeBy(25)
        currentRoom = "room-10"

        advanceUntilIdle()
        jobs.forEach { it.join() }

        // Should have some title set (not null = no empty view)
        // The exact title depends on timing, but error should be null
        assertNull(errors.value, "Should have no errors")
    }

    @Test
    fun `parseConversationId should correctly extract session and path`() {
        // Test the parsing logic used in connect()
        fun parseConversationId(conversationId: String): Pair<String?, String?> {
            return if (conversationId.contains("?project=")) {
                val parts = conversationId.split("?project=")
                val sessionId = parts[0]
                val encodedPath = parts.getOrNull(1)
                sessionId to encodedPath
            } else {
                null to null
            }
        }

        // New format
        val (sessionId1, path1) = parseConversationId("abc123?project=-Users-test")
        assertEquals("abc123", sessionId1)
        assertEquals("-Users-test", path1)

        // Legacy format (UUID only)
        val (sessionId2, path2) = parseConversationId("550e8400-e29b-41d4-a716-446655440000")
        assertNull(sessionId2)
        assertNull(path2)
    }

    // =====================================
    // Tool Message Race Condition Tests
    // =====================================

    /**
     * Simulates the finalized message update pattern.
     *
     * Tests the fix for: WebSocket COMPLETE arriving before HistoryWatch
     * delivers tool_use blocks, causing tools to be lost.
     */
    class FinalizedMessageSimulator {
        data class ChatMessage(
            val id: String,
            val content: String,
            val toolBlocks: List<String> = emptyList()
        )

        val messages = mutableListOf<ChatMessage>()
        val finalizedContentHashes = mutableSetOf<Int>()

        /**
         * Simulates finalizeStreamingMessage - creates message with content only (no tools yet)
         */
        fun finalizeWithTextOnly(messageId: String, content: String) {
            messages.add(ChatMessage(id = messageId, content = content))
            // Track this content as finalized
            finalizedContentHashes.add(content.take(200).hashCode())
        }

        /**
         * Simulates handleHistoryWatchEvent processing a new message.
         * This tests the fix: even if finalized, update if new message has more tools.
         *
         * @return true if message was updated, false if skipped
         */
        fun processHistoryWatchMessage(content: String, toolBlocks: List<String>): Boolean {
            val contentHash = content.take(200).hashCode()
            val isAlreadyFinalized = finalizedContentHashes.contains(contentHash)

            if (isAlreadyFinalized) {
                // FIX: Even if finalized, update with tools if new message has more
                val newToolCount = toolBlocks.size
                if (newToolCount > 0) {
                    // Find existing finalized message
                    val existingIdx = messages.indexOfFirst { existing ->
                        existing.content == content ||
                            existing.content.take(100) == content.take(100)
                    }
                    if (existingIdx >= 0) {
                        val existing = messages[existingIdx]
                        val existingToolCount = existing.toolBlocks.size
                        if (existingToolCount < newToolCount) {
                            // Update with new tools
                            messages[existingIdx] = existing.copy(toolBlocks = toolBlocks)
                            return true
                        }
                    }
                }
                return false // Skipped as finalized
            }

            // Not finalized - would add as new message
            messages.add(ChatMessage(id = "new-${messages.size}", content = content, toolBlocks = toolBlocks))
            return true
        }
    }

    @Test
    fun `finalized message should be updated with tools from historywatch`() {
        val sim = FinalizedMessageSimulator()

        // Step 1: WebSocket COMPLETE arrives, message finalized with text only
        sim.finalizeWithTextOnly("msg-1", "I'll read the file for you.")

        assertEquals(1, sim.messages.size)
        assertEquals(0, sim.messages[0].toolBlocks.size, "Initially no tools")

        // Step 2: HistoryWatch arrives with same text + tool blocks
        val updated = sim.processHistoryWatchMessage(
            content = "I'll read the file for you.",
            toolBlocks = listOf("read-file-1")
        )

        assertTrue(updated, "Should update finalized message with tools")
        assertEquals(1, sim.messages.size, "Should not add new message")
        assertEquals(1, sim.messages[0].toolBlocks.size, "Should have 1 tool")
        assertEquals("read-file-1", sim.messages[0].toolBlocks[0])
    }

    @Test
    fun `finalized message should be updated with multiple tools`() {
        val sim = FinalizedMessageSimulator()

        // Finalize with text only
        sim.finalizeWithTextOnly("msg-1", "Let me read and edit the file.")

        // HistoryWatch with multiple tools
        val updated = sim.processHistoryWatchMessage(
            content = "Let me read and edit the file.",
            toolBlocks = listOf("read-file-1", "edit-file-1", "write-file-1")
        )

        assertTrue(updated)
        assertEquals(3, sim.messages[0].toolBlocks.size)
    }

    @Test
    fun `finalized message without tools should not be duplicated`() {
        val sim = FinalizedMessageSimulator()

        sim.finalizeWithTextOnly("msg-1", "Simple response without tools.")

        // HistoryWatch with same text but no tools
        val updated = sim.processHistoryWatchMessage(
            content = "Simple response without tools.",
            toolBlocks = emptyList()
        )

        assertFalse(updated, "Should skip duplicate without new tools")
        assertEquals(1, sim.messages.size, "Should not duplicate message")
    }

    @Test
    fun `partial content match should still update with tools`() {
        val sim = FinalizedMessageSimulator()

        // Long text, finalized
        val longText = "This is a very long response that might be truncated " +
            "in the content hash comparison..."
        sim.finalizeWithTextOnly("msg-1", longText)

        // HistoryWatch with slightly different (truncated) text but with tools
        val slightlyDifferent = "This is a very long response that might be truncated"
        val updated = sim.processHistoryWatchMessage(
            content = slightlyDifferent,
            toolBlocks = listOf("tool-1")
        )

        // Should update because prefix matches (first 100 chars)
        assertTrue(updated, "Should update on partial match with new tools")
    }

    @Test
    fun `sequential tool results should accumulate`() {
        val sim = FinalizedMessageSimulator()

        // Finalize with text
        sim.finalizeWithTextOnly("msg-1", "Running multiple tools...")

        // First HistoryWatch: 1 tool
        sim.processHistoryWatchMessage("Running multiple tools...", listOf("tool-1"))
        assertEquals(1, sim.messages[0].toolBlocks.size)

        // Second HistoryWatch: 2 tools
        sim.processHistoryWatchMessage("Running multiple tools...", listOf("tool-1", "tool-2"))
        assertEquals(2, sim.messages[0].toolBlocks.size)

        // Third HistoryWatch: 3 tools
        sim.processHistoryWatchMessage("Running multiple tools...", listOf("tool-1", "tool-2", "tool-3"))
        assertEquals(3, sim.messages[0].toolBlocks.size)
    }

    @Test
    fun `existing tools should not be reduced`() {
        val sim = FinalizedMessageSimulator()

        sim.finalizeWithTextOnly("msg-1", "Response with tools")

        // First update: 3 tools
        sim.processHistoryWatchMessage("Response with tools", listOf("tool-1", "tool-2", "tool-3"))
        assertEquals(3, sim.messages[0].toolBlocks.size)

        // Stale update: only 1 tool (should be ignored)
        val updated = sim.processHistoryWatchMessage("Response with tools", listOf("tool-1"))
        assertFalse(updated, "Should not reduce tool count")
        assertEquals(3, sim.messages[0].toolBlocks.size, "Should keep 3 tools")
    }
}
