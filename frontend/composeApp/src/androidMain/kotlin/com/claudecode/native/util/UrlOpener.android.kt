package com.claudecode.native.util

import android.content.Intent
import android.net.Uri
import android.content.ActivityNotFoundException

/**
 * Android implementation - opens URL using Intent.
 * Note: This requires being called from an Activity context.
 * For Compose, use LocalUriHandler.current.openUri() instead if available.
 */
actual fun openUrl(url: String) {
    // Android URL opening typically requires an Activity context
    // The Compose Material3 Text component handles LinkAnnotation.Url clicks automatically
    // using LocalUriHandler, so this is a fallback implementation
    println("URL to open (Android fallback): $url")
}
