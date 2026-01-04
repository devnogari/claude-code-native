package com.claudecode.native.util

import androidx.compose.ui.Modifier

/**
 * WASM/JS implementation - no-op as hover events are handled differently in browser.
 * Browser hover can be implemented using pointerMoveFilter if needed.
 */
actual fun Modifier.onHover(
    onEnter: () -> Unit,
    onExit: () -> Unit
): Modifier = this // No-op for web
