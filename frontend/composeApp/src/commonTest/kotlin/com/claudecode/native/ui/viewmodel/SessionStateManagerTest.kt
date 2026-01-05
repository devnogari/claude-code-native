package com.claudecode.native.ui.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for SessionStateManager.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateManagerTest {

    @Test
    fun `setSessionIdentity should update all session variables`() {
        val manager = SessionStateManager()

        manager.setSessionIdentity(
            conversationId = "session123?project=encodedPath",
            encodedPath = "encodedPath",
            claudeSession = "session123",
            projectPath = "/project/path"
        )

        assertEquals("session123?project=encodedPath", manager.currentConversationId)
        assertEquals("encodedPath", manager.currentEncodedPath)
        assertEquals("session123", manager.currentClaudeSession)
        assertEquals("/project/path", manager.currentProjectPath)
        assertEquals("session123?project=encodedPath", manager.currentConversationIdFlow.value)
    }

    @Test
    fun `clearSessionIdentity should reset all session variables`() {
        val manager = SessionStateManager()
        manager.setSessionIdentity(
            conversationId = "session123?project=encodedPath",
            encodedPath = "encodedPath",
            claudeSession = "session123",
            projectPath = "/project/path"
        )

        manager.clearSessionIdentity()

        assertNull(manager.currentConversationId)
        assertNull(manager.currentEncodedPath)
        assertNull(manager.currentClaudeSession)
        assertNull(manager.currentProjectPath)
        assertNull(manager.currentConversationIdFlow.value)
        assertFalse(manager.isStreamingFromHistoryWatch)
    }

    @Test
    fun `setDraftSession should update isDraftSession state`() {
        val manager = SessionStateManager()

        assertFalse(manager.isDraftSession.value)

        manager.setDraftSession(true)
        assertTrue(manager.isDraftSession.value)
        assertTrue(manager.isDraftSessionValue())

        manager.setDraftSession(false)
        assertFalse(manager.isDraftSession.value)
        assertFalse(manager.isDraftSessionValue())
    }

    @Test
    fun `setConversationTitle should update title state`() {
        val manager = SessionStateManager()

        assertNull(manager.conversationTitle.value)

        manager.setConversationTitle("Test Title")
        assertEquals("Test Title", manager.conversationTitle.value)

        manager.setConversationTitle(null)
        assertNull(manager.conversationTitle.value)
    }

    @Test
    fun `parseConversationId should extract session and path`() {
        val manager = SessionStateManager()

        val (sessionId, encodedPath) = manager.parseConversationId("abc123?project=myProject")
        assertEquals("abc123", sessionId)
        assertEquals("myProject", encodedPath)
    }

    @Test
    fun `parseConversationId should return nulls for legacy format`() {
        val manager = SessionStateManager()

        val (sessionId, encodedPath) = manager.parseConversationId("legacy-uuid-format")
        assertNull(sessionId)
        assertNull(encodedPath)
    }

    @Test
    fun `isFilesystemSessionId should detect filesystem format`() {
        assertTrue(SessionStateManager.isFilesystemSessionId("abc123?project=myProject"))
        assertFalse(SessionStateManager.isFilesystemSessionId("legacy-uuid-format"))
        assertFalse(SessionStateManager.isFilesystemSessionId(""))
    }

    @Test
    fun `isDraftConversationId should detect draft sessions`() {
        val manager = SessionStateManager()

        assertTrue(manager.isDraftConversationId("draft?project=myProject"))
        assertFalse(manager.isDraftConversationId("abc123?project=myProject"))
        assertFalse(manager.isDraftConversationId("legacy-format"))
    }

    @Test
    fun `isActiveConversation should check current conversation`() {
        val manager = SessionStateManager()

        manager.setSessionIdentity(conversationId = "current-conv", encodedPath = null, claudeSession = null, projectPath = null)

        assertTrue(manager.isActiveConversation("current-conv"))
        assertFalse(manager.isActiveConversation("other-conv"))
    }

    @Test
    fun `emitSessionCreatedEvent should update event flow`() {
        val manager = SessionStateManager()

        assertNull(manager.sessionCreatedEvent.value)

        val info = SessionStateManager.SessionCreatedInfo("session123", "encodedPath")
        manager.emitSessionCreatedEvent(info)

        assertEquals(info, manager.sessionCreatedEvent.value)
    }

    @Test
    fun `clearSessionCreatedEvent should reset event flow`() {
        val manager = SessionStateManager()

        val info = SessionStateManager.SessionCreatedInfo("session123", "encodedPath")
        manager.emitSessionCreatedEvent(info)
        assertEquals(info, manager.sessionCreatedEvent.value)

        manager.clearSessionCreatedEvent()
        assertNull(manager.sessionCreatedEvent.value)
    }

    @Test
    fun `clearTransientState should reset transient values`() {
        val manager = SessionStateManager()

        manager.setConversationTitle("Title")
        manager.setDraftSession(true)
        manager.emitSessionCreatedEvent(SessionStateManager.SessionCreatedInfo("s", "p"))

        manager.clearTransientState()

        assertNull(manager.conversationTitle.value)
        assertFalse(manager.isDraftSession.value)
        assertNull(manager.sessionCreatedEvent.value)
    }

    @Test
    fun `pendingSessionCreatedEmit lifecycle with unsafe methods`() = runTest {
        val manager = SessionStateManager()

        // Set pending info
        val info = SessionStateManager.SessionCreatedInfo("session123", "encodedPath")
        manager.setPendingSessionCreatedEmitUnsafe(info)

        // Consume it
        val consumed = manager.consumePendingSessionCreatedEmitUnsafe()
        assertEquals(info, consumed)

        // Should be null after consume
        val consumedAgain = manager.consumePendingSessionCreatedEmitUnsafe()
        assertNull(consumedAgain)
    }

    @Test
    fun `pendingSessionCreatedEmit with suspend methods`() = runTest {
        val manager = SessionStateManager()

        // Should have nothing pending initially
        assertFalse(manager.hasPendingSessionCreatedEmit())

        // Set pending info
        val info = SessionStateManager.SessionCreatedInfo("session123", "encodedPath")
        manager.setPendingSessionCreatedEmit(info)

        // Should have pending now
        assertTrue(manager.hasPendingSessionCreatedEmit())

        // Consume it
        val consumed = manager.consumePendingSessionCreatedEmit()
        assertEquals(info, consumed)

        // Should be empty after consume
        assertFalse(manager.hasPendingSessionCreatedEmit())
        val consumedAgain = manager.consumePendingSessionCreatedEmit()
        assertNull(consumedAgain)
    }

    @Test
    fun `updateSessionInfo should update only encoded path and claude session`() {
        val manager = SessionStateManager()

        manager.setSessionIdentity(
            conversationId = "conv1",
            encodedPath = "oldPath",
            claudeSession = "oldSession",
            projectPath = "/old/path"
        )

        manager.updateSessionInfo(encodedPath = "newPath", claudeSession = "newSession")

        assertEquals("conv1", manager.currentConversationId)
        assertEquals("newPath", manager.currentEncodedPath)
        assertEquals("newSession", manager.currentClaudeSession)
        assertEquals("/old/path", manager.currentProjectPath)
    }

    @Test
    fun `setProjectPath should update only project path`() {
        val manager = SessionStateManager()

        manager.setSessionIdentity(
            conversationId = "conv1",
            encodedPath = "path",
            claudeSession = "session",
            projectPath = "/old/path"
        )

        manager.setProjectPath("/new/path")

        assertEquals("/new/path", manager.currentProjectPath)
        assertEquals("conv1", manager.currentConversationId)
    }

    @Test
    fun `isStreamingFromHistoryWatch should be settable`() {
        val manager = SessionStateManager()

        assertFalse(manager.isStreamingFromHistoryWatch)

        manager.isStreamingFromHistoryWatch = true
        assertTrue(manager.isStreamingFromHistoryWatch)

        manager.isStreamingFromHistoryWatch = false
        assertFalse(manager.isStreamingFromHistoryWatch)
    }
}
