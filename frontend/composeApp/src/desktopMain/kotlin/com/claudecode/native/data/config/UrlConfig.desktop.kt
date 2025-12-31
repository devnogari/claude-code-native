package com.claudecode.native.data.config

/**
 * Desktop implementation with hardcoded local development URLs.
 */
actual object UrlConfig {
    actual val apiBaseUrl: String = "http://localhost:8083/api/v1"
    actual val wsBaseUrl: String = "ws://localhost:8083/api/v1"
}
