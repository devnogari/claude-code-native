package com.claudecode.native.ui.layout

import androidx.compose.runtime.Composable

/**
 * iOS implementation of BackHandler.
 *
 * iOS doesn't have a system back button like Android.
 * Back navigation is typically handled through swipe gestures
 * or UI controls, which are managed at the navigation level.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op on iOS - no system back button
}
