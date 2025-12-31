package com.claudecode.native.data.config

/**
 * WASM implementation with hardcoded local development URLs.
 * Uses localhost since WASM runs in the browser on the same machine.
 */
actual object UrlConfig {
    actual val apiBaseUrl: String = "http://localhost:8083/api/v1"
    actual val wsBaseUrl: String = "ws://localhost:8083/api/v1"
}
