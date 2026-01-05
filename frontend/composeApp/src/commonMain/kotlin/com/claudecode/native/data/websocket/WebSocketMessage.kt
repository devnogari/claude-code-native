package com.claudecode.native.data.websocket

import com.claudecode.native.data.model.ClaudeMessage
import com.claudecode.native.data.model.SessionState
import com.claudecode.native.data.model.TodoItem
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
    /** Subscribe to a conversation (unified WebSocket) */
    const val SUBSCRIBE = "subscribe"
    /** Unsubscribe from a conversation (unified WebSocket) */
    const val UNSUBSCRIBE = "unsubscribe"
    /** Subscription confirmed (unified WebSocket) */
    const val SUBSCRIBED = "subscribed"
    /** Session state synchronization (unified WebSocket) */
    const val SESSION_STATE = "session_state"

    // History watch message types (unified into main WebSocket)
    /** Subscribe to history file changes (unified WebSocket) */
    const val HISTORY_SUBSCRIBE = "history_subscribe"
    /** Unsubscribe from history file changes (unified WebSocket) */
    const val HISTORY_UNSUBSCRIBE = "history_unsubscribe"
    /** History subscription confirmed (unified WebSocket) */
    const val HISTORY_SUBSCRIBED = "history_subscribed"
    /** History unsubscription confirmed (unified WebSocket) */
    const val HISTORY_UNSUBSCRIBED = "history_unsubscribed"
    /** New messages from history file (unified WebSocket) */
    const val NEW_MESSAGES = "new_messages"
}

/**
 * Payload for queue_add WebSocket message.
 */
@Serializable
data class QueueAddPayload(
    val id: String,
    val content: String,
    @SerialName("queued_at") val queuedAt: Long,
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

/**
 * Outgoing subscribe message for unified WebSocket.
 * Type-safe wrapper for subscribe requests.
 */
@Serializable
data class SubscribeMessage(
    val type: String = MessageType.SUBSCRIBE,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("encoded_path") val encodedPath: String? = null
)

/**
 * Outgoing unsubscribe message for unified WebSocket.
 * Type-safe wrapper for unsubscribe requests.
 */
@Serializable
data class UnsubscribeMessage(
    val type: String = MessageType.UNSUBSCRIBE,
    @SerialName("conversation_id") val conversationId: String
)

/**
 * Payload for subscribe WebSocket message (unified WebSocket).
 * Used to subscribe to a conversation without reconnecting.
 * @deprecated Use SubscribeMessage instead for type safety
 */
@Serializable
data class SubscribePayload(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("encoded_path") val encodedPath: String? = null
)

/**
 * Payload for session_state WebSocket message (unified WebSocket).
 * Contains the current state of a conversation session.
 *
 * Note: todos and queue are nullable because the server may send null explicitly.
 * kotlinx.serialization requires nullable types to handle explicit null values;
 * default values only apply when the field is missing from JSON, not when it's null.
 */
@Serializable
data class SessionStatePayload(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("session_state") val sessionState: String,
    @SerialName("is_streaming") val isStreaming: Boolean,
    val todos: List<TodoItemPayload>? = null,
    val queue: List<QueueMessagePayload>? = null
)

/**
 * Represents a todo item in session state.
 */
@Serializable
data class TodoItemPayload(
    val content: String,
    val status: String,
    @SerialName("active_form") val activeForm: String? = null,
    val priority: String? = null,
    val id: String? = null
)

// ============================================================
// History Watch DTOs (unified into main WebSocket)
// ============================================================

/**
 * Outgoing history subscribe message for unified WebSocket.
 * Type-safe wrapper for history_subscribe requests.
 */
@Serializable
data class HistorySubscribeMessage(
    val type: String = MessageType.HISTORY_SUBSCRIBE,
    @SerialName("encoded_path") val encodedPath: String,
    @SerialName("session_id") val sessionId: String
)

/**
 * Outgoing history unsubscribe message for unified WebSocket.
 * Type-safe wrapper for history_unsubscribe requests.
 */
@Serializable
data class HistoryUnsubscribeMessage(
    val type: String = MessageType.HISTORY_UNSUBSCRIBE
)

/**
 * Payload for history_subscribed WebSocket message.
 * Confirms subscription to history file watching.
 */
@Serializable
data class HistorySubscribedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("encoded_path") val encodedPath: String
)

/**
 * Payload for new_messages WebSocket message.
 * Contains new messages from the history file.
 */
@Serializable
data class NewMessagesPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("encoded_path") val encodedPath: String,
    val messages: List<ClaudeMessage> = emptyList(),
    @SerialName("session_state") val sessionState: SessionState = SessionState.IDLE,
    val todos: List<TodoItem> = emptyList()
)

/**
 * State for tracking current history subscription.
 */
data class HistorySubscriptionState(
    val encodedPath: String,
    val sessionId: String
)

/**
 * Sealed class representing history watch events from unified WebSocket.
 */
sealed class HistoryWatchEvent {
    data class Connected(
        val sessionId: String,
        val encodedPath: String
    ) : HistoryWatchEvent()

    data class NewMessages(
        val sessionId: String,
        val encodedPath: String,
        val messages: List<ClaudeMessage>,
        val sessionState: SessionState = SessionState.IDLE,
        val todos: List<TodoItem> = emptyList()
    ) : HistoryWatchEvent()

    data class Error(val message: String) : HistoryWatchEvent()

    data object Disconnected : HistoryWatchEvent()

    data object Unsubscribed : HistoryWatchEvent()
}
