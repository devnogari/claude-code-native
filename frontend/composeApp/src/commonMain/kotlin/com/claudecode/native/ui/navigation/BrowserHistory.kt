package com.claudecode.native.ui.navigation

/**
 * Platform-specific browser history management.
 * On WASM, syncs navigation state with browser URL.
 * On Desktop, no-op.
 */
expect object BrowserHistory {
    /**
     * Push a new URL to browser history.
     */
    fun pushState(path: String)

    /**
     * Replace current URL in browser history.
     */
    fun replaceState(path: String)

    /**
     * Get the current path from browser URL.
     */
    fun getCurrentPath(): String

    /**
     * Set callback for browser back/forward navigation.
     */
    fun setOnPopState(callback: (String) -> Unit)
}
