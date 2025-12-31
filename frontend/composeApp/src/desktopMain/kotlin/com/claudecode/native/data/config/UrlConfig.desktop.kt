package com.claudecode.native.data.config

/**
 * Desktop implementation with hardcoded local development URLs.
 */
actual object UrlConfig {
    actual val apiBaseUrl: String = "http://192.168.50.223:8083/api/v1"
    actual val wsBaseUrl: String = "ws://192.168.50.223:8083/api/v1"
}
