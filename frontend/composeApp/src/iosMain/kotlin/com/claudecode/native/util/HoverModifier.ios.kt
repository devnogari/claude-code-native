package com.claudecode.native.util

import androidx.compose.ui.Modifier

/**
 * iOS implementation - no-op as iOS doesn't have traditional mouse hover.
 */
actual fun Modifier.onHover(
    onEnter: () -> Unit,
    onExit: () -> Unit
): Modifier = this // No-op for iOS
