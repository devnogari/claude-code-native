package com.claudecode.native.data.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val username: String
)
