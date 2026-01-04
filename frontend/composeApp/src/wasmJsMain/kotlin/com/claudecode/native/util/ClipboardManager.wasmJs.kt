package com.claudecode.native.util

import kotlinx.browser.document
import org.w3c.dom.HTMLTextAreaElement

/**
 * WASM/JS-specific clipboard manager using document.execCommand.
 * This is the most compatible approach for WASM/JS.
 */
actual object ClipboardManager {
    actual fun copyToClipboard(text: String) {
        try {
            // Create a temporary textarea element
            val textArea = document.createElement("textarea") as HTMLTextAreaElement
            textArea.value = text
            // Make the textarea invisible but still in the DOM
            textArea.style.cssText = "position: fixed; left: -9999px; top: -9999px; opacity: 0;"
            document.body?.appendChild(textArea)

            // Select the text
            textArea.select()

            // Execute copy command
            document.execCommand("copy")

            // Cleanup
            document.body?.removeChild(textArea)
        } catch (e: Exception) {
            // Silently fail if clipboard copy fails
            println("ClipboardManager: Failed to copy to clipboard: ${e.message}")
        }
    }
}
