package com.claudecode.native.data.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for HistoryWatchClient.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryWatchClientTest {

    private fun createMockHttpClient(): HttpClient {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }
        return HttpClient(mockEngine) {
            install(WebSockets)
        }
    }

    @Test
    fun `isConnected should be false initially`() {
        val scope = TestScope(StandardTestDispatcher())
        val client = HistoryWatchClient(createMockHttpClient(), scope)

        assertFalse(client.isConnected.value)
    }

    @Test
    fun `HistoryWatchMessageType should have all expected types`() {
        assertEquals("new_messages", HistoryWatchMessageType.NEW_MESSAGES)
        assertEquals("error", HistoryWatchMessageType.ERROR)
        assertEquals("ping", HistoryWatchMessageType.PING)
        assertEquals("pong", HistoryWatchMessageType.PONG)
        assertEquals("subscribed", HistoryWatchMessageType.SUBSCRIBED)
    }

    @Test
    fun `HistoryWatchEvent Connected should contain session info`() {
        val event = HistoryWatchEvent.Connected(
            sessionId = "session123",
            encodedPath = "Users-test-project"
        )

        assertTrue(event is HistoryWatchEvent.Connected)
        assertEquals("session123", event.sessionId)
        assertEquals("Users-test-project", event.encodedPath)
    }

    @Test
    fun `HistoryWatchEvent Error should contain error message`() {
        val event = HistoryWatchEvent.Error("Connection failed")

        assertTrue(event is HistoryWatchEvent.Error)
        assertEquals("Connection failed", event.message)
    }

    @Test
    fun `HistoryWatchEvent Disconnected should be object`() {
        val event = HistoryWatchEvent.Disconnected

        assertTrue(event is HistoryWatchEvent.Disconnected)
    }

    @Test
    fun `HistoryWatchEvent NewMessages should contain message list`() {
        val messages = listOf(
            com.claudecode.native.data.model.ClaudeMessage(
                type = "message",
                sessionId = "session1"
            )
        )
        val event = HistoryWatchEvent.NewMessages(messages)

        assertTrue(event is HistoryWatchEvent.NewMessages)
        assertEquals(1, event.messages.size)
        assertEquals("session1", event.messages[0].sessionId)
    }

    @Test
    fun `HistoryWatchMessage should parse all fields`() {
        val message = HistoryWatchMessage(
            type = HistoryWatchMessageType.SUBSCRIBED,
            sessionId = "sess123",
            encodedPath = "path123",
            messages = null,
            error = null
        )

        assertEquals(HistoryWatchMessageType.SUBSCRIBED, message.type)
        assertEquals("sess123", message.sessionId)
        assertEquals("path123", message.encodedPath)
        assertEquals(null, message.messages)
        assertEquals(null, message.error)
    }

    @Test
    fun `HistoryWatchMessage with error should parse correctly`() {
        val message = HistoryWatchMessage(
            type = HistoryWatchMessageType.ERROR,
            error = "Session not found"
        )

        assertEquals(HistoryWatchMessageType.ERROR, message.type)
        assertEquals("Session not found", message.error)
    }
}
