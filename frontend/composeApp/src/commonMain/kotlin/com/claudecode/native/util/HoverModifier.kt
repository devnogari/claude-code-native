package com.claudecode.native.util

import androidx.compose.ui.Modifier

/**
 * Cross-platform hover modifier that adds hover detection on supported platforms.
 * On desktop, this uses pointer event handlers.
 * On other platforms, this is a no-op.
 *
 * @param onEnter Callback when pointer enters the component
 * @param onExit Callback when pointer exits the component
 */
expect fun Modifier.onHover(
    onEnter: () -> Unit,
    onExit: () -> Unit
): Modifier
