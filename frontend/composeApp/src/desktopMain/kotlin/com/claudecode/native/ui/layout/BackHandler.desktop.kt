package com.claudecode.native.ui.layout

import androidx.compose.runtime.Composable

/**
 * Desktop implementation of BackHandler.
 *
 * Desktop platforms don't have a system back button, so this is a no-op.
 * Navigation is handled through UI controls instead.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op on desktop - no system back button
}
