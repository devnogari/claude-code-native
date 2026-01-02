package com.claudecode.native.util

import kotlinx.browser.window

/**
 * WASM/JS implementation - opens URL in a new browser tab.
 */
actual fun openUrl(url: String) {
    try {
        window.open(url, "_blank")
    } catch (e: Exception) {
        println("Failed to open URL: $url - ${e.message}")
    }
}
