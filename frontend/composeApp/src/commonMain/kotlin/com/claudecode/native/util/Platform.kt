package com.claudecode.native.util

/**
 * Platform detection utilities for platform-specific behavior.
 */
expect object Platform {
    /**
     * Returns true if running on iOS.
     */
    val isIOS: Boolean

    /**
     * Returns true if running on desktop (JVM).
     */
    val isDesktop: Boolean

    /**
     * Returns true if running on web (WASM).
     */
    val isWeb: Boolean
}
