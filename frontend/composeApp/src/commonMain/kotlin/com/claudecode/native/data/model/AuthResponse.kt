package com.claudecode.native.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    @SerialName("confirm_password") val confirmPassword: String
)

@Serializable
data class TokenResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: Long,
    val user: User
)

@Serializable
data class ErrorResponse(
    val error: String,
    val details: String? = null
)
