package com.claudecode.native.ui.navigation

/**
 * Android implementation - no-op since there's no browser history.
 */
actual object BrowserHistory {
    actual fun pushState(path: String) {
        // No-op on Android
    }

    actual fun replaceState(path: String) {
        // No-op on Android
    }

    actual fun getCurrentPath(): String {
        return "/"
    }

    actual fun setOnPopState(callback: (String) -> Unit) {
        // No-op on Android
    }
}
