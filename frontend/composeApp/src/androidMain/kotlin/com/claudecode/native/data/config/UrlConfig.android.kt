package com.claudecode.native.data.config

/**
 * Android implementation with hardcoded local development URLs.
 * Uses Mac's network IP since localhost doesn't work on Android devices.
 */
actual object UrlConfig {
    actual val apiBaseUrl: String = "http://192.168.50.223:8083/api/v1"
    actual val wsBaseUrl: String = "ws://192.168.50.223:8083/api/v1"
}
