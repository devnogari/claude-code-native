package com.claudecode.native.data.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val role: MessageRole,
    val content: String,
    @SerialName("sequence_num") val sequenceNum: Int,
    @SerialName("token_count") val tokenCount: Int? = null,
    @SerialName("created_at") val createdAt: Instant
)

@Serializable
enum class MessageRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("system") SYSTEM
}
