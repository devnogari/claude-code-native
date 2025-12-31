package com.claudecode.native.ui.layout

import androidx.compose.runtime.Composable

/**
 * Platform-specific back button handler.
 *
 * On Android, this integrates with the system back button.
 * On other platforms (Desktop, WASM, iOS), this is a no-op as they
 * don't have a system back button concept in the same way.
 *
 * @param enabled Whether the back handler is currently enabled
 * @param onBack Callback to invoke when back is pressed
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
