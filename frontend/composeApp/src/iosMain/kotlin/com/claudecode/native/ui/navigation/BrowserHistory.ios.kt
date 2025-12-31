package com.claudecode.native.ui.navigation

/**
 * iOS implementation - no-op since there's no browser URL to manage.
 */
actual object BrowserHistory {
    actual fun pushState(path: String) {
        // No-op on iOS
    }

    actual fun replaceState(path: String) {
        // No-op on iOS
    }

    actual fun getCurrentPath(): String {
        return "/"
    }

    actual fun setOnPopState(callback: (String) -> Unit) {
        // No-op on iOS
    }
}
