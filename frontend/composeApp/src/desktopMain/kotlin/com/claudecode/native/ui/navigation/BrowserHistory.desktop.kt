package com.claudecode.native.ui.navigation

/**
 * Desktop implementation - no-op since there's no browser.
 */
actual object BrowserHistory {
    actual fun pushState(path: String) {
        // No-op on desktop
    }

    actual fun replaceState(path: String) {
        // No-op on desktop
    }

    actual fun getCurrentPath(): String {
        return "/"
    }

    actual fun setOnPopState(callback: (String) -> Unit) {
        // No-op on desktop
    }
}
