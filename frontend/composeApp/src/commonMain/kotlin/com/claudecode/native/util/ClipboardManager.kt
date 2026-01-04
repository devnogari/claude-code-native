package com.claudecode.native.util

/**
 * Cross-platform clipboard manager for copying text to system clipboard.
 */
expect object ClipboardManager {
    /**
     * Copies the given text to the system clipboard.
     *
     * @param text The text to copy
     */
    fun copyToClipboard(text: String)
}
