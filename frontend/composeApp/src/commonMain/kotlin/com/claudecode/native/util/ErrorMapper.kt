package com.claudecode.native.util

import com.claudecode.native.data.api.ApiException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

/**
 * Maps exceptions to user-friendly error messages.
 *
 * Provides consistent error messaging across the application by:
 * - Converting technical exceptions to readable messages
 * - Mapping HTTP status codes to appropriate descriptions
 * - Handling network-related errors gracefully
 */
object ErrorMapper {

    /**
     * Maps any exception to a user-friendly error message.
     *
     * @param e The exception to map.
     * @return A user-friendly error message string.
     */
    fun mapError(e: Exception): String = when (e) {
        is ApiException -> mapApiException(e)
        is HttpRequestTimeoutException -> "Request timed out. Please try again."
        is ConnectTimeoutException -> "Connection timed out. Check your network."
        is SocketTimeoutException -> "Connection lost. Please try again."
        else -> mapGenericException(e)
    }

    /**
     * Maps an ApiException to a user-friendly message based on status code.
     */
    private fun mapApiException(e: ApiException): String {
        return when (e.statusCode) {
            401 -> "Session expired. Please login again."
            403 -> "You don't have permission for this action."
            404 -> "Resource not found."
            409 -> "This resource already exists or conflicts with current state."
            422 -> "Invalid data provided. Please check your input."
            429 -> "Too many requests. Please wait a moment."
            in 500..599 -> "Server error. Please try again later."
            else -> e.message ?: "Request failed (Error ${e.statusCode ?: "unknown"})"
        }
    }

    /**
     * Maps generic exceptions to user-friendly messages.
     */
    private fun mapGenericException(e: Exception): String {
        val message = e.message ?: return "An unexpected error occurred"

        // Common network error patterns
        return when {
            message.contains("Unable to resolve host", ignoreCase = true) ->
                "Network error. Check your internet connection."

            message.contains("Connection refused", ignoreCase = true) ->
                "Unable to connect to server. Please try again later."

            message.contains("Network is unreachable", ignoreCase = true) ->
                "Network unavailable. Check your connection."

            message.contains("SSL", ignoreCase = true) ||
            message.contains("Certificate", ignoreCase = true) ->
                "Secure connection failed. Please try again."

            message.contains("timeout", ignoreCase = true) ->
                "Request timed out. Please try again."

            else -> message.take(100) // Limit message length for display
        }
    }

    /**
     * Determines if an error should trigger a logout/re-authentication.
     *
     * @param e The exception to check.
     * @return True if the user should be logged out.
     */
    fun isAuthError(e: Exception): Boolean {
        return e is ApiException && e.isUnauthorized()
    }

    /**
     * Determines if an error is retryable.
     *
     * @param e The exception to check.
     * @return True if the operation can be retried.
     */
    fun isRetryable(e: Exception): Boolean {
        return when (e) {
            is HttpRequestTimeoutException -> true
            is ConnectTimeoutException -> true
            is SocketTimeoutException -> true
            is ApiException -> e.isServerError() || e.statusCode == 429
            else -> {
                val message = e.message ?: return false
                message.contains("timeout", ignoreCase = true) ||
                message.contains("Connection refused", ignoreCase = true)
            }
        }
    }
}

/**
 * Extension function to map exceptions in catch blocks.
 *
 * Usage:
 * ```
 * try {
 *     // API call
 * } catch (e: Exception) {
 *     _error.value = e.toUserMessage()
 * }
 * ```
 */
fun Exception.toUserMessage(): String = ErrorMapper.mapError(this)

/**
 * Extension function to check if exception requires re-authentication.
 */
fun Exception.requiresReAuth(): Boolean = ErrorMapper.isAuthError(this)

/**
 * Extension function to check if operation can be retried.
 */
fun Exception.canRetry(): Boolean = ErrorMapper.isRetryable(this)
