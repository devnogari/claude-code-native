package com.claudecode.native.data.api

import com.claudecode.native.data.model.ClaudeMessage
import com.claudecode.native.data.model.ClaudeProject
import com.claudecode.native.data.model.ClaudeSession
import com.claudecode.native.data.model.MessageContent
import com.claudecode.native.data.model.PaginatedClaudeMessagesResponse
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for ClaudeHistoryApi data models.
 */
class ClaudeHistoryApiTest {

    @Test
    fun `ClaudeProject should store all fields correctly`() {
        val lastAccessed = Instant.parse("2024-01-01T00:00:00Z")

        val project = ClaudeProject(
            id = "proj123",
            name = "My Project",
            path = "/Users/test/project",
            encodedPath = "Users-test-project",
            sessions = emptyList(),
            lastAccessed = lastAccessed
        )

        assertEquals("proj123", project.id)
        assertEquals("My Project", project.name)
        assertEquals("/Users/test/project", project.path)
        assertEquals("Users-test-project", project.encodedPath)
        assertTrue(project.sessions.isEmpty())
        assertEquals(lastAccessed, project.lastAccessed)
    }

    @Test
    fun `ClaudeProject with null lastAccessed should work`() {
        val project = ClaudeProject(
            id = "proj123",
            name = "My Project",
            path = "/Users/test/project",
            encodedPath = "Users-test-project",
            sessions = emptyList(),
            lastAccessed = null
        )

        assertNull(project.lastAccessed)
    }

    @Test
    fun `ClaudeSession should store all fields correctly`() {
        val createdAt = Instant.parse("2024-01-01T00:00:00Z")
        val updatedAt = Instant.parse("2024-01-02T00:00:00Z")

        val session = ClaudeSession(
            id = "sess123",
            filename = "session.jsonl",
            messageCount = 5,
            firstMessage = "Hello",
            isFavorite = true,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

        assertEquals("sess123", session.id)
        assertEquals("session.jsonl", session.filename)
        assertEquals(5, session.messageCount)
        assertEquals("Hello", session.firstMessage)
        assertTrue(session.isFavorite)
        assertEquals(createdAt, session.createdAt)
        assertEquals(updatedAt, session.updatedAt)
    }

    @Test
    fun `ClaudeSession with optional fields as defaults should work`() {
        val session = ClaudeSession(
            id = "sess123",
            filename = "session.jsonl",
            messageCount = 0,
            firstMessage = "",
            isFavorite = false,
            createdAt = null,
            updatedAt = null
        )

        assertEquals("", session.firstMessage)
        assertFalse(session.isFavorite)
        assertNull(session.createdAt)
        assertNull(session.updatedAt)
    }

    @Test
    fun `ClaudeMessage should store all fields correctly`() {
        val timestamp = Instant.parse("2024-01-01T12:00:00Z")

        val message = ClaudeMessage(
            type = "message",
            sessionId = "sess123",
            timestamp = timestamp,
            message = MessageContent(
                role = "user",
                content = JsonPrimitive("Hello World")
            ),
            cwd = "/Users/test/project",
            parentMsgId = "parent123"
        )

        assertEquals("message", message.type)
        assertEquals("sess123", message.sessionId)
        assertEquals(timestamp, message.timestamp)
        assertEquals("user", message.message?.role)
        assertEquals("/Users/test/project", message.cwd)
        assertEquals("parent123", message.parentMsgId)
    }

    @Test
    fun `ClaudeMessage queue-operation type should have operation and content`() {
        val message = ClaudeMessage(
            type = "queue-operation",
            sessionId = "sess123",
            operation = "enqueue",
            content = "Queued message content"
        )

        assertEquals("queue-operation", message.type)
        assertEquals("enqueue", message.operation)
        assertEquals("Queued message content", message.content)
    }

    @Test
    fun `MessageContent should store role and content`() {
        val content = MessageContent(
            role = "assistant",
            content = JsonPrimitive("I can help with that")
        )

        assertEquals("assistant", content.role)
        assertTrue(content.content is JsonPrimitive)
    }

    @Test
    fun `PaginatedClaudeMessagesResponse should store pagination info`() {
        val response = PaginatedClaudeMessagesResponse(
            messages = listOf(
                ClaudeMessage(type = "message", sessionId = "sess1")
            ),
            total = 100,
            limit = 50,
            offset = 0,
            hasMore = true
        )

        assertEquals(1, response.messages.size)
        assertEquals(100, response.total)
        assertEquals(50, response.limit)
        assertEquals(0, response.offset)
        assertTrue(response.hasMore)
    }

    @Test
    fun `PaginatedClaudeMessagesResponse hasMore false when no more results`() {
        val response = PaginatedClaudeMessagesResponse(
            messages = emptyList(),
            total = 10,
            limit = 50,
            offset = 10,
            hasMore = false
        )

        assertTrue(response.messages.isEmpty())
        assertFalse(response.hasMore)
    }

    @Test
    fun `ToggleSessionFavoriteRequest should store project path`() {
        val request = ToggleSessionFavoriteRequest(
            projectPath = "/Users/test/project"
        )

        assertEquals("/Users/test/project", request.projectPath)
    }

    @Test
    fun `ToggleSessionFavoriteResponse should store session info`() {
        val response = ToggleSessionFavoriteResponse(
            sessionId = "sess123",
            isFavorite = true
        )

        assertEquals("sess123", response.sessionId)
        assertTrue(response.isFavorite)
    }

    @Test
    fun `ClaudeHistoryApi constants should have correct values`() {
        assertEquals(50, ClaudeHistoryApi.DEFAULT_LIMIT)
        assertEquals(500, ClaudeHistoryApi.DEFAULT_MAX_CONTENT_LENGTH)
    }
}
