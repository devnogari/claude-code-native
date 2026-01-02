package com.claudecode.native.util

/**
 * WASM implementation of ImageResizer.
 * Currently passes through without resizing.
 * TODO: Implement using Canvas API for browser-based resizing.
 */
actual object ImageResizer {

    actual fun resize(data: ByteArray, mediaType: String, config: ImageResizeConfig): ResizedImage {
        // WASM implementation: pass through for now
        // Browser-based resizing would require Canvas API
        return ResizedImage(data, mediaType, wasResized = false)
    }
}
