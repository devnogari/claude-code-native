package com.claudecode.native.data.config

/**
 * Platform-specific URL configuration.
 * Each platform provides its own implementation based on runtime environment.
 */
expect object UrlConfig {
    /**
     * Base URL for REST API calls (e.g., "http://localhost:8083/api/v1")
     */
    val apiBaseUrl: String

    /**
     * Base URL for WebSocket connections (e.g., "ws://localhost:8083/api/v1")
     */
    val wsBaseUrl: String
}
