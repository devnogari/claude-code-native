package com.claudecode.native.util

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Desktop-specific clipboard manager for copying text to system clipboard.
 */
actual object ClipboardManager {
    actual fun copyToClipboard(text: String) {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(text)
            clipboard.setContents(selection, selection)
        } catch (e: Exception) {
            println("Failed to copy to clipboard: ${e.message}")
        }
    }
}
