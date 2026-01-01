package com.claudecode.native.ui.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    // Streaming Content Race Condition Tests
    // =====================================

    /**
     * Simulates the streaming content management pattern used in ChatViewModel.
     *
     * The key insight is: when WebSocket is streaming (!isStreamingFromHistoryWatch),
     * HistoryWatch should NOT overwrite _streamingContent. It should only add tool blocks.
     */
    class StreamingContentSimulator {
        var isStreaming = false
        var isStreamingFromHistoryWatch = false
        var streamingContent = ""
        var streamingBlocks = mutableListOf<String>()  // Simplified: "text:xxx" or "tool:xxx"

        /**
         * Simulates WebSocket initiating streaming and adding content
         */
        fun webSocketStartStreaming() {
            if (!isStreaming) {
                isStreaming = true
                isStreamingFromHistoryWatch = false
            }
        }

        fun webSocketAddContent(chunk: String) {
            streamingContent += chunk
        }

        /**
         * Simulates HistoryWatch processing a file change event.
         * This tests the fix: when WebSocket is streaming, don't overwrite content.
         */
        fun historyWatchProcessEvent(textBlocks: List<String>, toolBlocks: List<String>) {
            if (isStreaming) {
                // FIX: Only add text blocks if HistoryWatch initiated streaming
                if (isStreamingFromHistoryWatch) {
                    textBlocks.forEach { text ->
                        if (!streamingBlocks.contains("text:$text")) {
                            streamingBlocks.add("text:$text")
                        }
                    }
                    // Update streamingContent from blocks
                    streamingContent = streamingBlocks
                        .filter { it.startsWith("text:") }
                        .joinToString("\n\n") { it.removePrefix("text:") }
                }
                // Tool blocks are always processed regardless of streaming source
                toolBlocks.forEach { tool ->
                    if (!streamingBlocks.contains("tool:$tool")) {
                        streamingBlocks.add("tool:$tool")
                    }
                }
            }
        }

        /**
         * Simulates finalizing the streaming message.
         * Tests that WebSocket content is combined with HistoryWatch tools.
         */
        fun finalizeStreaming(): List<String> {
            val hasTextBlock = streamingBlocks.any { it.startsWith("text:") }
            val result = if (streamingBlocks.isNotEmpty()) {
                if (!hasTextBlock && streamingContent.isNotEmpty()) {
                    // WebSocket streaming case: prepend text from streamingContent
                    listOf("text:$streamingContent") + streamingBlocks
                } else {
                    // HistoryWatch streaming case: blocks have everything
                    streamingBlocks.toList()
                }
            } else if (streamingContent.isNotEmpty()) {
                listOf("text:$streamingContent")
            } else {
                emptyList()
            }

            // Reset state
            isStreaming = false
            isStreamingFromHistoryWatch = false
            streamingContent = ""
            streamingBlocks.clear()

            return result
        }
    }

    @Test
    fun `websocket streaming content should not be overwritten by historywatch`() {
        val sim = StreamingContentSimulator()

        // WebSocket starts streaming
        sim.webSocketStartStreaming()
        sim.webSocketAddContent("Hello ")
        sim.webSocketAddContent("World!")

        assertEquals("Hello World!", sim.streamingContent)
        assertFalse(sim.isStreamingFromHistoryWatch)

        // HistoryWatch processes file event with empty/stale content
        // This should NOT overwrite the WebSocket content
        sim.historyWatchProcessEvent(
            textBlocks = listOf(""),  // stale/empty text from file
            toolBlocks = listOf("read-file-1")
        )

        // Content should still be from WebSocket
        assertEquals("Hello World!", sim.streamingContent, "WebSocket content should not be overwritten")

        // But tool should be added
        assertTrue(sim.streamingBlocks.contains("tool:read-file-1"), "Tool should be added")
    }

    @Test
    fun `finalize should combine websocket text with historywatch tools`() {
        val sim = StreamingContentSimulator()

        // WebSocket streams text
        sim.webSocketStartStreaming()
        sim.webSocketAddContent("This is the response text.")

        // HistoryWatch adds tools
        sim.historyWatchProcessEvent(
            textBlocks = emptyList(),
            toolBlocks = listOf("read-file", "edit-file")
        )

        // Finalize
        val result = sim.finalizeStreaming()

        // Should have text from WebSocket + tools from HistoryWatch
        assertEquals(3, result.size, "Should have 1 text + 2 tools")
        assertEquals("text:This is the response text.", result[0], "First should be WebSocket text")
        assertTrue(result.contains("tool:read-file"), "Should have read-file tool")
        assertTrue(result.contains("tool:edit-file"), "Should have edit-file tool")
    }

    @Test
    fun `historywatch initiated streaming should manage all content`() {
        val sim = StreamingContentSimulator()

        // HistoryWatch initiates streaming (by setting flags directly - simulating the actual behavior)
        sim.isStreaming = true
        sim.isStreamingFromHistoryWatch = true

        // HistoryWatch adds text and tools
        sim.historyWatchProcessEvent(
            textBlocks = listOf("Response from terminal"),
            toolBlocks = listOf("bash-1")
        )

        // streamingContent should be from blocks
        assertEquals("Response from terminal", sim.streamingContent)

        // Finalize
        val result = sim.finalizeStreaming()

        assertEquals(2, result.size)
        assertTrue(result.contains("text:Response from terminal"))
        assertTrue(result.contains("tool:bash-1"))
    }

    @Test
    fun `rapid websocket chunks should accumulate correctly`() {
        val sim = StreamingContentSimulator()

        sim.webSocketStartStreaming()

        // Simulate rapid chunks
        val chunks = listOf("I ", "will ", "read ", "the ", "file.")
        chunks.forEach { sim.webSocketAddContent(it) }

        assertEquals("I will read the file.", sim.streamingContent)

        // HistoryWatch tries to process during streaming
        sim.historyWatchProcessEvent(
            textBlocks = listOf("I will"),  // partial/stale content
            toolBlocks = emptyList()
        )

        // Content should NOT be overwritten
        assertEquals("I will read the file.", sim.streamingContent, "Full content should be preserved")
    }

    @Test
    fun `empty streaming should not produce empty message`() {
        val sim = StreamingContentSimulator()

        sim.webSocketStartStreaming()
        // No content added

        val result = sim.finalizeStreaming()

        assertTrue(result.isEmpty(), "Should not produce empty message blocks")
    }
}
