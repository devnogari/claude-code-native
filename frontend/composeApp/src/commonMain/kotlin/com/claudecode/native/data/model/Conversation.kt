package com.claudecode.native.data.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("claude_session") val claudeSession: String? = null,
    val title: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("jsonl_path") val jsonlPath: String? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant
)

@Serializable
data class CreateConversationRequest(
    val title: String? = null
)

@Serializable
data class UpdateConversationRequest(
    val title: String? = null
)
