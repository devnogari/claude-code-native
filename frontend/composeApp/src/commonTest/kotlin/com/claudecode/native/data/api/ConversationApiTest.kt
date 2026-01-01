package com.claudecode.native.data.api

import com.claudecode.native.data.model.Conversation
import com.claudecode.native.data.model.CreateConversationRequest
import com.claudecode.native.data.model.UpdateConversationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Instant

/**
 * Unit tests for ConversationApi data models and request/response structures.
 */
class ConversationApiTest {

    @Test
    fun `Conversation should store all fields correctly`() {
        val createdAt = Instant.parse("2024-01-01T00:00:00Z")
        val updatedAt = Instant.parse("2024-01-02T00:00:00Z")

        val conversation = Conversation(
            id = "conv123",
            projectId = "proj456",
            claudeSession = "session789",
            title = "Test Conversation",
            messageCount = 10,
            jsonlPath = "/path/to/session.jsonl",
            isFavorite = true,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

        assertEquals("conv123", conversation.id)
        assertEquals("proj456", conversation.projectId)
        assertEquals("session789", conversation.claudeSession)
        assertEquals("Test Conversation", conversation.title)
        assertEquals(10, conversation.messageCount)
        assertEquals("/path/to/session.jsonl", conversation.jsonlPath)
        assertTrue(conversation.isFavorite)
        assertEquals(createdAt, conversation.createdAt)
        assertEquals(updatedAt, conversation.updatedAt)
    }

    @Test
    fun `Conversation with optional fields null should work`() {
        val now = Instant.parse("2024-01-01T00:00:00Z")

        val conversation = Conversation(
            id = "conv123",
            projectId = "proj456",
            claudeSession = null,
            title = null,
            messageCount = 0,
            jsonlPath = null,
            isFavorite = false,
            createdAt = now,
            updatedAt = now
        )

        assertNull(conversation.claudeSession)
        assertNull(conversation.title)
        assertNull(conversation.jsonlPath)
        assertFalse(conversation.isFavorite)
    }

    @Test
    fun `CreateConversationRequest should store project and title`() {
        val request = CreateConversationRequest(
            projectId = "proj123",
            title = "New Conversation"
        )

        assertEquals("proj123", request.projectId)
        assertEquals("New Conversation", request.title)
    }

    @Test
    fun `CreateConversationRequest with null title should work`() {
        val request = CreateConversationRequest(
            projectId = "proj123",
            title = null
        )

        assertEquals("proj123", request.projectId)
        assertNull(request.title)
    }

    @Test
    fun `UpdateConversationRequest should store title`() {
        val request = UpdateConversationRequest(
            title = "Updated Title"
        )

        assertEquals("Updated Title", request.title)
    }

    @Test
    fun `UpdateConversationRequest with null title should work`() {
        val request = UpdateConversationRequest(
            title = null
        )

        assertNull(request.title)
    }
}
