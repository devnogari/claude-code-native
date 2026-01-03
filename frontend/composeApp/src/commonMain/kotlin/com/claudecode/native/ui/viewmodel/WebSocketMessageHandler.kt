package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.websocket.IncomingMessage
import com.claudecode.native.data.websocket.MessageType
import com.claudecode.native.util.DebugLogger

/**
 * Handler for WebSocket messages with callback-based dispatch.
 *
 * This class provides a clean separation between message routing
 * and business logic handling. It categorizes incoming messages
 * and invokes the appropriate callbacks.
 *
 * Message types handled:
 * - STREAM: Streaming content chunks during response generation
 * - COMPLETE: End of streaming response
 * - ERROR: Error messages from the server
 * - STATUS: Status updates (acknowledged but typically no action needed)
 * - PONG: Keep-alive response
 * - QUEUE_ADD/QUEUE_REMOVE/QUEUE_SYNC: Message queue operations
 *
 * @param onStreamMessage Callback for stream message chunks
 * @param onCompleteMessage Callback for stream completion
 * @param onErrorMessage Callback for error messages (receives error string)
 * @param onStatusMessage Optional callback for status updates
 * @param onPongMessage Optional callback for pong responses
 * @param onQueueMessage Optional callback for queue operations (receives message type string)
 */
class WebSocketMessageHandler(
    private val onStreamMessage: suspend () -> Unit,
    private val onCompleteMessage: suspend () -> Unit,
    private val onErrorMessage: suspend (error: String) -> Unit,
    private val onStatusMessage: (suspend () -> Unit)? = null,
    private val onPongMessage: (suspend () -> Unit)? = null,
    private val onQueueMessage: (suspend (String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "WebSocketMessageHandler"
        private const val DEFAULT_ERROR_MESSAGE = "Unknown error"
    }

    /**
     * Handles an incoming WebSocket message by dispatching to the appropriate callback.
     *
     * @param message The incoming WebSocket message to handle
     */
    suspend fun handleMessage(message: IncomingMessage) {
        DebugLogger.d(TAG, "handleMessage: type=${message.type}")

        when (message.type) {
            MessageType.STREAM -> {
                onStreamMessage()
            }

            MessageType.COMPLETE -> {
                onCompleteMessage()
            }

            MessageType.ERROR -> {
                val errorText = message.error ?: DEFAULT_ERROR_MESSAGE
                onErrorMessage(errorText)
            }

            MessageType.STATUS -> {
                onStatusMessage?.invoke()
            }

            MessageType.PONG -> {
                onPongMessage?.invoke()
            }

            MessageType.QUEUE_ADD,
            MessageType.QUEUE_REMOVE,
            MessageType.QUEUE_SYNC -> {
                onQueueMessage?.invoke(message.type)
            }
        }
    }

    /**
     * Checks if a message is a streaming content message.
     *
     * @param message The message to check
     * @return true if the message is a STREAM type
     */
    fun isStreamingMessage(message: IncomingMessage): Boolean {
        return message.type == MessageType.STREAM
    }

    /**
     * Checks if a message is a queue-related message.
     *
     * @param message The message to check
     * @return true if the message is QUEUE_ADD, QUEUE_REMOVE, or QUEUE_SYNC
     */
    fun isQueueMessage(message: IncomingMessage): Boolean {
        return message.type in listOf(
            MessageType.QUEUE_ADD,
            MessageType.QUEUE_REMOVE,
            MessageType.QUEUE_SYNC
        )
    }

    /**
     * Checks if a message represents end of streaming.
     *
     * Terminal messages indicate the streaming session should end,
     * either successfully (COMPLETE) or with an error (ERROR).
     *
     * @param message The message to check
     * @return true if the message is COMPLETE or ERROR
     */
    fun isTerminalMessage(message: IncomingMessage): Boolean {
        return message.type in listOf(MessageType.COMPLETE, MessageType.ERROR)
    }

    /**
     * Gets the error message from an error message, or null if not an error.
     *
     * @param message The message to extract error from
     * @return The error string if this is an ERROR message, null otherwise
     */
    fun getErrorMessage(message: IncomingMessage): String? {
        return if (message.type == MessageType.ERROR) {
            message.error ?: DEFAULT_ERROR_MESSAGE
        } else {
            null
        }
    }
}
