package com.claudecode.native.ui.layout

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler as AndroidBackHandler

/**
 * Android implementation of BackHandler.
 *
 * Delegates to AndroidX Activity Compose's BackHandler to integrate
 * with the system back button and predictive back gestures.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    AndroidBackHandler(enabled = enabled, onBack = onBack)
}
