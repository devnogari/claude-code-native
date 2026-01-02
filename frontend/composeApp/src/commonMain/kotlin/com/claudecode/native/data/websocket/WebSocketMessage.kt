package com.claudecode.native.data.websocket

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents an image attachment in a WebSocket message.
 */
@Serializable
data class ImageContentDto(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String
)

/**
 * Outgoing WebSocket message sent from client to server.
 * Used for chat messages, control commands (stop, ping), and other client-initiated actions.
 */
@Serializable
data class OutgoingMessage(
    val type: String,
    val content: String? = null,
    val images: List<ImageContentDto>? = null
) {
    companion object {
        /**
         * Creates a chat message to send user input to the server.
         */
        fun chat(content: String) = OutgoingMessage("chat", content)

        /**
         * Creates a chat message with images attached.
         */
        fun chatWithImages(content: String, images: List<ImageContentDto>) =
            OutgoingMessage("chat", content, images.ifEmpty { null })

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
 * Contains streaming content, status updates, errors, completion signals, and queue updates.
 */
@Serializable
data class IncomingMessage(
    val type: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    val content: String? = null,
    val error: String? = null,
    val status: String? = null,
    /** Payload for queue messages (queue_add, queue_remove, queue_sync) */
    val payload: JsonElement? = null
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
    /** Message added to queue */
    const val QUEUE_ADD = "queue_add"
    /** Message removed from queue */
    const val QUEUE_REMOVE = "queue_remove"
    /** Full queue sync */
    const val QUEUE_SYNC = "queue_sync"
}

/**
 * Payload for queue_add WebSocket message.
 */
@Serializable
data class QueueAddPayload(
    val id: String,
    val content: String,
    val queuedAt: Long,
    val images: List<QueueImagePayload> = emptyList()
)

/**
 * Payload for queue_remove WebSocket message.
 */
@Serializable
data class QueueRemovePayload(
    val id: String
)

/**
 * Payload for queue_sync WebSocket message.
 */
@Serializable
data class QueueSyncPayload(
    val messages: List<QueueMessagePayload>
)

/**
 * Represents a queued message in WebSocket payloads.
 */
@Serializable
data class QueueMessagePayload(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val content: String,
    @SerialName("queued_at") val queuedAt: Long,
    val images: List<QueueImagePayload> = emptyList()
)

/**
 * Represents an image in a queued message.
 */
@Serializable
data class QueueImagePayload(
    val id: String,
    val url: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("file_name") val fileName: String? = null,
    val width: Int? = null,
    val height: Int? = null
)
