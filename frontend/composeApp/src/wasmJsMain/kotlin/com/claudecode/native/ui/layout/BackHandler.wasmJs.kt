package com.claudecode.native.ui.layout

import androidx.compose.runtime.Composable

/**
 * WASM/JS implementation of BackHandler.
 *
 * Browser back navigation is handled separately through BrowserHistory.
 * This composable is a no-op as the browser's native back button
 * is managed at the navigation level.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op on WASM - browser back handled via BrowserHistory
}
