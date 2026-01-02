package com.claudecode.native.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS implementation - opens URL using UIApplication.
 */
actual fun openUrl(url: String) {
    try {
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl != null) {
            UIApplication.sharedApplication.openURL(nsUrl)
        }
    } catch (e: Exception) {
        println("Failed to open URL: $url - ${e.message}")
    }
}
