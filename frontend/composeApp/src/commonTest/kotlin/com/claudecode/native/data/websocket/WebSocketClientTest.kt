package com.claudecode.native.data.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for WebSocketClient.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketClientTest {

    @Test
    fun `ConnectionState Disconnected should be initial state`() {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(WebSockets)
        }

        val scope = TestScope(StandardTestDispatcher())
        val client = WebSocketClient(httpClient, scope)

        assertEquals(ConnectionState.Disconnected, client.connectionState.value)
    }

    @Test
    fun `isConnected should return false when disconnected`() {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(WebSockets)
        }

        val scope = TestScope(StandardTestDispatcher())
        val client = WebSocketClient(httpClient, scope)

        assertFalse(client.isConnected())
    }

    @Test
    fun `WebSocketConfig should have sensible defaults`() {
        val config = WebSocketConfig()

        assertEquals(5, config.maxReconnectAttempts)
        assertTrue(config.enableAutoReconnect)
        assertEquals(1000L, config.initialDelayMs)
        assertEquals(30000L, config.maxDelayMs)
    }

    @Test
    fun `WebSocketConfig should be customizable`() {
        val config = WebSocketConfig(
            maxReconnectAttempts = 10,
            enableAutoReconnect = false,
            initialDelayMs = 500L,
            maxDelayMs = 60000L
        )

        assertEquals(10, config.maxReconnectAttempts)
        assertFalse(config.enableAutoReconnect)
        assertEquals(500L, config.initialDelayMs)
        assertEquals(60000L, config.maxDelayMs)
    }

    @Test
    fun `ConnectionState sealed class should have all states`() {
        // Test that all connection states are properly defined
        val states = listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting,
            ConnectionState.Connected,
            ConnectionState.Reconnecting(1),
            ConnectionState.Error("test error")
        )

        assertEquals(5, states.size)
    }

    @Test
    fun `ConnectionState Reconnecting should contain attempt number`() {
        val state = ConnectionState.Reconnecting(3)

        assertTrue(state is ConnectionState.Reconnecting)
        assertEquals(3, state.attempt)
    }

    @Test
    fun `ConnectionState Error should contain error message`() {
        val state = ConnectionState.Error("Connection timeout")

        assertTrue(state is ConnectionState.Error)
        assertEquals("Connection timeout", state.message)
    }

    @Test
    fun `OutgoingMessage chat factory should create correct message`() {
        val message = OutgoingMessage.chat("Hello world")

        assertEquals(MessageType.CHAT, message.type)
        assertEquals("Hello world", message.content)
    }

    @Test
    fun `OutgoingMessage stop factory should create correct message`() {
        val message = OutgoingMessage.stop()

        assertEquals(MessageType.STOP, message.type)
        assertEquals(null, message.content)
    }

    @Test
    fun `OutgoingMessage ping factory should create correct message`() {
        val message = OutgoingMessage.ping()

        assertEquals(MessageType.PING, message.type)
        assertEquals(null, message.content)
    }

    @Test
    fun `IncomingMessage should parse all fields`() {
        val message = IncomingMessage(
            type = MessageType.STREAM,
            conversationId = "conv123",
            content = "Hello",
            error = null,
            status = "streaming"
        )

        assertEquals(MessageType.STREAM, message.type)
        assertEquals("conv123", message.conversationId)
        assertEquals("Hello", message.content)
        assertEquals(null, message.error)
        assertEquals("streaming", message.status)
    }

    @Test
    fun `MessageType should have all expected types`() {
        // Verify all message types are defined
        assertEquals("chat", MessageType.CHAT)
        assertEquals("stream", MessageType.STREAM)
        assertEquals("status", MessageType.STATUS)
        assertEquals("error", MessageType.ERROR)
        assertEquals("stop", MessageType.STOP)
        assertEquals("complete", MessageType.COMPLETE)
        assertEquals("ping", MessageType.PING)
        assertEquals("pong", MessageType.PONG)
    }

    @Test
    fun `MessageType should have mode-related types`() {
        assertEquals("mode_change", MessageType.MODE_CHANGE)
        assertEquals("mode_changed", MessageType.MODE_CHANGED)
        assertEquals("mode_state", MessageType.MODE_STATE)
    }

    @Test
    fun `ModeChangeMessage should create correct message`() {
        val message = ModeChangeMessage(mode = "plan")

        assertEquals(MessageType.MODE_CHANGE, message.type)
        assertEquals("plan", message.mode)
    }

    @Test
    fun `ModeChangedPayload should parse all fields`() {
        val payload = ModeChangedPayload(
            conversationId = "conv123",
            mode = "bypassPermissions",
            changedBy = "user@example.com"
        )

        assertEquals("conv123", payload.conversationId)
        assertEquals("bypassPermissions", payload.mode)
        assertEquals("user@example.com", payload.changedBy)
    }

    @Test
    fun `ModeChangedPayload should allow null changedBy`() {
        val payload = ModeChangedPayload(
            conversationId = "conv123",
            mode = "default",
            changedBy = null
        )

        assertEquals("conv123", payload.conversationId)
        assertEquals("default", payload.mode)
        assertEquals(null, payload.changedBy)
    }

    @Test
    fun `ModeStatePayload should parse all fields`() {
        val payload = ModeStatePayload(
            conversationId = "conv456",
            mode = "plan"
        )

        assertEquals("conv456", payload.conversationId)
        assertEquals("plan", payload.mode)
    }
}
