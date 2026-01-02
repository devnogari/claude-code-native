package com.claudecode.native.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API response for a queued message from the server.
 */
@Serializable
data class QueuedMessageDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val content: String,
    @SerialName("queued_at") val queuedAt: Long, // Unix milliseconds
    val images: List<QueuedImageDto> = emptyList()
)

/**
 * API response for an image attached to a queued message.
 */
@Serializable
data class QueuedImageDto(
    val id: String,
    val url: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("file_name") val fileName: String? = null,
    val width: Int? = null,
    val height: Int? = null
)

/**
 * Response for listing queued messages.
 */
@Serializable
data class QueueListResponse(
    val messages: List<QueuedMessageDto>
)

/**
 * Request body for adding a message to the queue.
 */
@Serializable
data class AddToQueueRequest(
    val content: String
)

/**
 * Generic success response with a message.
 */
@Serializable
data class QueueSuccessResponse(
    val message: String
)

/**
 * Response when adding a message to the queue.
 */
@Serializable
data class QueueAddResponse(
    val message: QueuedMessageDto
)
