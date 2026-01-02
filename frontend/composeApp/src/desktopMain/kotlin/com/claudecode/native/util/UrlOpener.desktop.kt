package com.claudecode.native.util

import java.awt.Desktop
import java.net.URI

/**
 * Desktop (JVM) implementation - opens URL using java.awt.Desktop.
 */
actual fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        } else {
            // Fallback for systems without Desktop support
            val os = System.getProperty("os.name").lowercase()
            val command = when {
                os.contains("mac") -> arrayOf("open", url)
                os.contains("win") -> arrayOf("cmd", "/c", "start", url)
                else -> arrayOf("xdg-open", url)
            }
            Runtime.getRuntime().exec(command)
        }
    } catch (e: Exception) {
        println("Failed to open URL: $url - ${e.message}")
    }
}
