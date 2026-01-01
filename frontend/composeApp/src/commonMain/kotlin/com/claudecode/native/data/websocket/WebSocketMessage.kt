package com.claudecode.native.data.websocket

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Outgoing WebSocket message sent from client to server.
 * Used for chat messages, control commands (stop, ping), and other client-initiated actions.
 */
@Serializable
data class OutgoingMessage(
    val type: String,
    val content: String? = null
) {
    companion object {
        /**
         * Creates a chat message to send user input to the server.
         */
        fun chat(content: String) = OutgoingMessage("chat", content)

        /**
         * Creates a stop message to interrupt the current streaming response.
         */
        fun stop() = OutgoingMessage("stop")

        /**
         * Creates a ping message to keep the connection alive.
         */
        fun ping() = OutgoingMessage("ping")

        /**
         * Creates an auth message to authenticate after connection.
         * This is more secure than passing token as URL parameter.
         */
        fun auth(token: String) = OutgoingMessage("auth", token)
    }
}

/**
 * Incoming WebSocket message received from server.
 * Contains streaming content, status updates, errors, and completion signals.
 */
@Serializable
data class IncomingMessage(
    val type: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    val content: String? = null,
    val error: String? = null,
    val status: String? = null
)

/**
 * Constants for WebSocket message types.
 * Used to identify the purpose of incoming and outgoing messages.
 */
object MessageType {
    /** User chat message */
    const val CHAT = "chat"
    /** Streaming content chunk from AI response */
    const val STREAM = "stream"
    /** Connection or processing status update */
    const val STATUS = "status"
    /** Error message */
    const val ERROR = "error"
    /** Request to stop current streaming */
    const val STOP = "stop"
    /** AI response completed */
    const val COMPLETE = "complete"
    /** Keep-alive ping from client */
    const val PING = "ping"
    /** Keep-alive pong response from server */
    const val PONG = "pong"
    /** Authentication message */
    const val AUTH = "auth"
}
