package com.claudecode.native.util

/**
 * Android implementation - clipboard operations.
 * Note: Full clipboard functionality requires Android Context.
 * For Compose, use ClipboardManager from LocalClipboardManager if available.
 */
actual object ClipboardManager {
    actual fun copyToClipboard(text: String) {
        // Android clipboard requires Context which isn't available in expect/actual
        // The actual clipboard copying should be handled at the Compose level
        // using LocalClipboardManager.current.setText(AnnotatedString(text))
        println("ClipboardManager: Android fallback - text length: ${text.length}")
    }
}
