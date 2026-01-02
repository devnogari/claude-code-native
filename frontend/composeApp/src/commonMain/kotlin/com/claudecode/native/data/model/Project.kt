package com.claudecode.native.data.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val name: String,
    val path: String,
    @SerialName("claude_id") val claudeId: String? = null,
    @SerialName("last_accessed") val lastAccessed: Instant? = null,
    @SerialName("is_completed") val isCompleted: Boolean = false,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val path: String
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null
)
