package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.QueueApi
import com.claudecode.native.data.websocket.IncomingMessage
import com.claudecode.native.data.websocket.MessageType
import com.claudecode.native.data.websocket.QueueAddPayload
import com.claudecode.native.data.websocket.QueueRemovePayload
import com.claudecode.native.data.websocket.QueueSyncPayload
import com.claudecode.native.data.websocket.UnifiedWebSocketClient
import com.claudecode.native.data.websocket.ImageContentDto
import com.claudecode.native.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi

/**
 * Manages message queue state for conversations.
 * Handles queue operations including:
 * - Adding messages to server/local queue
 * - WebSocket-based queue synchronization
 * - Queue retry logic on reconnection
 * - Cancel and clear operations
 */
class QueueManager(
    private val json: Json,
    private val queueApi: QueueApi,
    private val unifiedWebSocketClient: UnifiedWebSocketClient,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
    private val isFilesystemSession: (String) -> Boolean,
    private val generateMessageId: () -> String
) {
    companion object {
        private const val TAG = "QueueManager"
        const val MAX_QUEUED_MESSAGES = 10
    }

    /** Messages queued per conversation (Map<ConversationId, List<QueuedMessage>>). */
    private val _queuedMessagesMap = MutableStateFlow<Map<String, List<QueuedMessage>>>(emptyMap())

    /**
     * Checks if the queue for a conversation has reached max capacity.
     */
    fun isQueueFull(conversationId: String?): Boolean {
        val convId = conversationId ?: return false
        return (_queuedMessagesMap.value[convId]?.size ?: 0) >= MAX_QUEUED_MESSAGES
    }

    /** All queued messages (map of conversation IDs to queues). */
    val queuedMessagesMap: StateFlow<Map<String, List<QueuedMessage>>> = _queuedMessagesMap

    /**
     * Updates the queue for the current conversation.
     * If conversationId is null, the update is silently ignored.
     *
     * @param conversationId Current conversation ID
     * @param transform Function to transform the current queue to a new queue
     */
    fun updateCurrentQueue(conversationId: String?, transform: (List<QueuedMessage>) -> List<QueuedMessage>) {
        val convId = conversationId ?: return
        _queuedMessagesMap.update { map ->
            map + (convId to transform(map[convId] ?: emptyList()))
        }
    }

    /**
     * Updates the queue for a specific conversation.
     *
     * @param conversationId The conversation ID to update
     * @param transform Function to transform the current queue to a new queue
     */
    fun updateQueueForConversation(conversationId: String, transform: (List<QueuedMessage>) -> List<QueuedMessage>) {
        _queuedMessagesMap.update { map ->
            map + (conversationId to transform(map[conversationId] ?: emptyList()))
        }
    }

    /**
     * Gets the current queue for a conversation.
     *
     * @param conversationId The conversation ID
     * @return List of queued messages for the conversation
     */
    fun getCurrentQueue(conversationId: String?): List<QueuedMessage> {
        val convId = conversationId ?: return emptyList()
        return _queuedMessagesMap.value[convId] ?: emptyList()
    }

    /**
     * Adds a message to the server queue, falling back to local-only on failure.
     *
     * For filesystem-based sessions (new format: "sessionId?project=encodedPath"),
     * uses local queue only since these sessions don't have database conversation IDs.
     *
     * @param conversationId Current conversation ID
     * @param content Message content
     * @param images Attached images
     * @return QueuedMessage (server or local)
     */
    suspend fun addToServerQueue(conversationId: String?, content: String, images: List<AttachedImage>): QueuedMessage {
        // If no conversation or offline, store locally
        if (conversationId == null) {
            return createLocalQueuedMessage(content, images)
        }

        // Filesystem-based sessions don't have database conversation IDs, so use local queue only.
        if (isFilesystemSession(conversationId)) {
            DebugLogger.d(TAG, "Filesystem-based session detected, using local queue")
            return createLocalQueuedMessage(content, images)
        }

        return try {
            val response = queueApi.addToQueue(conversationId, content)
            val serverMsg = response.message
            // Map server-returned images to ServerImage objects
            val serverImages = serverMsg.images.map { imgDto ->
                ServerImage(
                    id = imgDto.id,
                    url = imgDto.url,
                    mediaType = imgDto.mediaType,
                    fileName = imgDto.fileName,
                    width = imgDto.width,
                    height = imgDto.height
                )
            }
            QueuedMessage(
                id = serverMsg.id,
                content = serverMsg.content,
                queuedAt = serverMsg.queuedAt,
                source = QueuedMessageSource.SERVER,
                images = images, // Keep local images for immediate display (before server upload)
                serverImages = serverImages // Server-persisted images (currently empty until upload is implemented)
            )
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to add to server queue, storing locally: ${e.message}", e)
            createLocalQueuedMessage(content, images)
        }
    }

    /**
     * Creates a local-only queued message (offline fallback).
     *
     * @param content Message content
     * @param images Attached images
     * @return Local QueuedMessage
     */
    fun createLocalQueuedMessage(content: String, images: List<AttachedImage>): QueuedMessage {
        return QueuedMessage(
            id = generateMessageId(),
            content = content,
            queuedAt = Clock.System.now().toEpochMilliseconds(),
            source = QueuedMessageSource.LOCAL,
            images = images
        )
    }

    /**
     * Handles queue-related WebSocket messages (queue_add, queue_remove, queue_sync).
     *
     * @param message Incoming WebSocket message
     * @param conversationId Current conversation ID
     */
    fun handleQueueWebSocketMessage(message: IncomingMessage, conversationId: String?) {
        val payload = message.payload ?: return

        when (message.type) {
            MessageType.QUEUE_ADD -> {
                try {
                    val addPayload = json.decodeFromJsonElement<QueueAddPayload>(payload)
                    val serverImages = addPayload.images.map { img ->
                        ServerImage(
                            id = img.id,
                            url = img.url,
                            mediaType = img.mediaType,
                            fileName = img.fileName,
                            width = img.width,
                            height = img.height
                        )
                    }
                    val queuedMsg = QueuedMessage(
                        id = addPayload.id,
                        content = addPayload.content,
                        queuedAt = addPayload.queuedAt,
                        source = QueuedMessageSource.SERVER,
                        serverImages = serverImages
                    )
                    updateCurrentQueue(conversationId) { queue ->
                        // Avoid duplicates
                        if (queue.none { it.id == addPayload.id }) {
                            queue + queuedMsg
                        } else {
                            queue
                        }
                    }
                    DebugLogger.d(TAG, "Queue add from WebSocket: id=${addPayload.id}, images=${serverImages.size}")
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Failed to parse queue_add payload: ${e.message}", e)
                }
            }

            MessageType.QUEUE_REMOVE -> {
                try {
                    val removePayload = json.decodeFromJsonElement<QueueRemovePayload>(payload)
                    updateCurrentQueue(conversationId) { queue ->
                        queue.filter { it.id != removePayload.id }
                    }
                    DebugLogger.d(TAG, "Queue remove from WebSocket: id=${removePayload.id}")
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Failed to parse queue_remove payload: ${e.message}", e)
                }
            }

            MessageType.QUEUE_SYNC -> {
                try {
                    val syncPayload = json.decodeFromJsonElement<QueueSyncPayload>(payload)
                    val serverMessages = syncPayload.messages.map { msg ->
                        val serverImages = msg.images.map { img ->
                            ServerImage(
                                id = img.id,
                                url = img.url,
                                mediaType = img.mediaType,
                                fileName = img.fileName,
                                width = img.width,
                                height = img.height
                            )
                        }
                        QueuedMessage(
                            id = msg.id,
                            content = msg.content,
                            queuedAt = msg.queuedAt,
                            source = QueuedMessageSource.SERVER,
                            serverImages = serverImages
                        )
                    }
                    // Keep local-only messages
                    val currentQueue = getCurrentQueue(conversationId)
                    val localOnlyMessages = currentQueue.filter { it.source == QueuedMessageSource.LOCAL }

                    updateCurrentQueue(conversationId) { serverMessages + localOnlyMessages }
                    DebugLogger.d(TAG, "Queue sync from WebSocket: ${serverMessages.size} server + ${localOnlyMessages.size} local")
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Failed to parse queue_sync payload: ${e.message}", e)
                }
            }

            else -> {
                // Not a queue message
            }
        }
    }

    /**
     * Retries sending all queued messages after reconnection.
     * Messages with images are sent with images, text-only messages are sent as text.
     *
     * @param conversationId Current conversation ID
     */
    fun retryQueuedMessages(conversationId: String?) {
        val queue = getCurrentQueue(conversationId)
        if (queue.isEmpty()) return

        DebugLogger.d(TAG, "Retrying ${queue.size} queued messages after reconnection")

        queue.forEach { msg ->
            scope.launch {
                try {
                    DebugLogger.d(TAG, "Retrying queued message (id=${msg.id})")
                    if (msg.images.isEmpty()) {
                        unifiedWebSocketClient.sendChat(msg.content)
                    } else {
                        val imageDtos = msg.images.map { img ->
                            ImageContentDto(
                                type = "base64",
                                mediaType = img.mediaType,
                                data = Base64.encode(img.data)
                            )
                        }
                        unifiedWebSocketClient.sendChatWithImages(msg.content, imageDtos)
                    }
                    DebugLogger.d(TAG, "Successfully retried queued message (id=${msg.id}), waiting for dequeue event")
                    // Don't remove from queue here - wait for CLI's dequeue event
                } catch (e: Exception) {
                    val isConnectionError = e is kotlinx.coroutines.CancellationException ||
                        (e is IllegalStateException && e.message?.contains("not connected") == true)

                    if (isConnectionError) {
                        DebugLogger.d(TAG, "Connection error while retrying message (id=${msg.id}), will retry again: ${e.message}")
                    } else {
                        DebugLogger.e(TAG, "Failed to retry queued message (id=${msg.id}): ${e.message}", e)
                        // Note: Currently removes failed messages from queue. Future improvement:
                        // Add status field to QueuedMessage (QUEUED, SENDING, FAILED) to allow
                        // users to retry, edit, or copy failed messages instead of losing them.
                        updateCurrentQueue(conversationId) { q -> q.filter { it.id != msg.id } }
                        onError("Failed to send message: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Cancels a specific queued message by its ID.
     * Only LOCAL messages can be cancelled from this app.
     * Uses atomic update to prevent race conditions.
     *
     * @param conversationId Current conversation ID
     * @param messageId The ID of the queued message to cancel
     */
    fun cancelQueuedMessage(conversationId: String?, messageId: String) {
        val queue = getCurrentQueue(conversationId)
        val message = queue.find { it.id == messageId }
        when {
            message == null -> {
                DebugLogger.d(TAG, "Cannot cancel - message not found: $messageId")
            }
            message.source == QueuedMessageSource.CLI -> {
                DebugLogger.d(TAG, "Cannot cancel CLI message from app: $messageId")
                onError("Cannot cancel messages queued from terminal")
            }
            else -> {
                DebugLogger.d(TAG, "Cancelling queued message: $messageId (source=${message.source})")
                // Remove from local state immediately
                updateCurrentQueue(conversationId) { q -> q.filter { it.id != messageId } }

                // If it's a server message, also remove from server
                // (Skip for filesystem-based sessions which don't have database conversation IDs)
                if (message.source == QueuedMessageSource.SERVER && conversationId != null) {
                    scope.launch {
                        try {
                            if (isFilesystemSession(conversationId)) return@launch // Skip filesystem sessions
                            queueApi.removeFromQueue(conversationId, messageId)
                            DebugLogger.d(TAG, "Removed message from server queue: $messageId")
                        } catch (e: Exception) {
                            DebugLogger.e(TAG, "Failed to remove message from server queue: ${e.message}", e)
                            // Already removed from local state, server will eventually sync
                        }
                    }
                }
            }
        }
    }

    /**
     * Clears all queued messages for the current conversation (both local and server).
     * CLI-originated messages cannot be cleared from this app.
     * Server messages are also cleared from the server via API call.
     * Uses atomic update to prevent race conditions.
     *
     * @param conversationId Current conversation ID
     */
    fun clearQueuedMessages(conversationId: String?) {
        val queue = getCurrentQueue(conversationId)
        val cliMessages = queue.filter { it.source == QueuedMessageSource.CLI }
        val clearableMessages = queue.filter { it.source != QueuedMessageSource.CLI }

        if (clearableMessages.isNotEmpty()) {
            DebugLogger.d(TAG, "Clearing ${clearableMessages.size} queued messages")
            updateCurrentQueue(conversationId) { cliMessages }

            // Clear server queue if any server messages
            // (Skip for filesystem-based sessions which don't have database conversation IDs)
            val hasServerMessages = clearableMessages.any { it.source == QueuedMessageSource.SERVER }
            if (hasServerMessages && conversationId != null) {
                scope.launch {
                    try {
                        if (isFilesystemSession(conversationId)) return@launch // Skip filesystem sessions
                        queueApi.clearQueue(conversationId)
                        DebugLogger.d(TAG, "Cleared server queue")
                    } catch (e: Exception) {
                        DebugLogger.e(TAG, "Failed to clear server queue: ${e.message}", e)
                    }
                }
            }
        }
    }

    /**
     * Clears queued messages that were sent to CLI (LOCAL/SERVER sources).
     * Called when streaming completes as a fallback when CLI's dequeue events are missed.
     * CLI-sourced messages are NOT cleared as they're managed by CLI terminal.
     *
     * Uses atomic update pattern to ensure thread safety when multiple completion
     * paths (WebSocket COMPLETE, HistoryWatch IDLE, REST sync) trigger simultaneously.
     *
     * @param conversationId Current conversation ID
     */
    fun clearSentQueueMessages(conversationId: String?) {
        val convId = conversationId ?: return

        // Perform filtering inside update lambda for thread safety
        _queuedMessagesMap.update { map ->
            val queue = map[convId] ?: return@update map

            // Keep only CLI messages, clear LOCAL and SERVER (which were sent to CLI)
            val cliMessages = queue.filter { it.source == QueuedMessageSource.CLI }
            val clearedCount = queue.size - cliMessages.size

            if (clearedCount > 0) {
                DebugLogger.d(TAG, "Cleared $clearedCount sent queue messages for conv=${convId.take(30)} (CLI messages kept: ${cliMessages.size})")
                map + (convId to cliMessages)
            } else {
                map // Nothing to clear
            }
        }
    }

    /**
     * Syncs local-only messages to the server after reconnection.
     * Processes messages sequentially to avoid race conditions with WebSocket queue_sync.
     *
     * Note: Skipped for filesystem-based sessions which don't have database conversation IDs.
     *
     * @param conversationId Current conversation ID
     */
    fun syncLocalMessagesToServer(conversationId: String?) {
        val queue = getCurrentQueue(conversationId)
        val localMessages = queue.filter { it.source == QueuedMessageSource.LOCAL }
        if (localMessages.isEmpty()) return

        DebugLogger.d(TAG, "Syncing ${localMessages.size} local messages to server")

        // Process sequentially in a single coroutine to avoid race conditions
        scope.launch {
            val convId = conversationId ?: return@launch

            // Skip sync for filesystem-based sessions (no database conversation ID)
            if (isFilesystemSession(convId)) {
                DebugLogger.d(TAG, "Skipping queue sync for filesystem-based session")
                return@launch
            }

            for (msg in localMessages) {
                try {
                    val response = queueApi.addToQueue(convId, msg.content)
                    // Replace local message with server message
                    updateCurrentQueue(conversationId) { q ->
                        q.map { existing ->
                            if (existing.id == msg.id) {
                                QueuedMessage(
                                    id = response.message.id,
                                    content = response.message.content,
                                    queuedAt = response.message.queuedAt,
                                    source = QueuedMessageSource.SERVER,
                                    images = msg.images // Keep local images
                                )
                            } else {
                                existing
                            }
                        }
                    }
                    DebugLogger.d(TAG, "Synced local message to server: ${msg.id} -> ${response.message.id}")
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Failed to sync local message to server: ${e.message}", e)
                    // Keep as local, will retry on next connection
                }
            }
        }
    }
}
