package com.claudecode.native.data.api

import com.claudecode.native.data.model.LoginRequest
import com.claudecode.native.data.model.RegisterRequest
import com.claudecode.native.data.model.TokenResponse

/**
 * API client for authentication operations.
 * Handles user login, registration, and token management.
 */
class AuthApi(private val client: ApiClient) {

    /**
     * Authenticates a user with username and password.
     * On success, stores the auth token in the client for subsequent requests.
     *
     * @param username The user's username
     * @param password The user's password
     * @return TokenResponse containing the auth token and user info
     */
    suspend fun login(username: String, password: String): TokenResponse {
        val response = client.post<LoginRequest, TokenResponse>(
            "/auth/login",
            LoginRequest(username, password)
        )
        client.setAuthToken(response.token)
        return response
    }

    /**
     * Registers a new user account.
     * On success, stores the auth token in the client for subsequent requests.
     *
     * @param username The desired username
     * @param password The desired password
     * @param confirmPassword Password confirmation (must match password)
     * @return TokenResponse containing the auth token and user info
     */
    suspend fun register(username: String, password: String, confirmPassword: String): TokenResponse {
        val response = client.post<RegisterRequest, TokenResponse>(
            "/auth/register",
            RegisterRequest(username, password, confirmPassword)
        )
        client.setAuthToken(response.token)
        return response
    }

    /**
     * Logs out the current user by clearing the auth token.
     */
    fun logout() {
        client.setAuthToken(null)
    }

    /**
     * Checks if a user is currently authenticated.
     *
     * @return true if an auth token is present, false otherwise
     */
    fun isLoggedIn(): Boolean = client.getAuthToken() != null
}
