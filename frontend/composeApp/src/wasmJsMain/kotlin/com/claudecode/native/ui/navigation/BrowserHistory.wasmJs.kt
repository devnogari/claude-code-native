package com.claudecode.native.ui.navigation

import kotlinx.browser.window

/**
 * WASM implementation using browser History API.
 */
actual object BrowserHistory {
    private var onPopStateCallback: ((String) -> Unit)? = null

    init {
        // Listen for browser back/forward navigation
        window.onpopstate = { event ->
            val path = window.location.pathname
            onPopStateCallback?.invoke(path)
            Unit
        }
    }

    actual fun pushState(path: String) {
        window.history.pushState(null, "", path)
    }

    actual fun replaceState(path: String) {
        window.history.replaceState(null, "", path)
    }

    actual fun getCurrentPath(): String {
        return window.location.pathname
    }

    actual fun setOnPopState(callback: (String) -> Unit) {
        onPopStateCallback = callback
    }
}
