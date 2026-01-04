package com.claudecode.native.util

import androidx.compose.ui.Modifier

/**
 * Android implementation - no-op as Android touch devices don't have traditional hover.
 */
actual fun Modifier.onHover(
    onEnter: () -> Unit,
    onExit: () -> Unit
): Modifier = this // No-op for Android
