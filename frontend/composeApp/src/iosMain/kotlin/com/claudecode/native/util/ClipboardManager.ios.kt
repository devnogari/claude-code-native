package com.claudecode.native.util

import platform.UIKit.UIPasteboard

/**
 * iOS implementation - uses UIPasteboard for clipboard operations.
 */
actual object ClipboardManager {
    actual fun copyToClipboard(text: String) {
        try {
            UIPasteboard.generalPasteboard.string = text
        } catch (e: Exception) {
            println("ClipboardManager: Failed to copy to clipboard: ${e.message}")
        }
    }
}
