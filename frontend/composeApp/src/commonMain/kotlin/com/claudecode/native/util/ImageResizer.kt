package com.claudecode.native.util

/**
 * Configuration for image resizing.
 */
data class ImageResizeConfig(
    /** Maximum width in pixels. Images wider than this will be scaled down. */
    val maxWidth: Int = 2048,
    /** Maximum height in pixels. Images taller than this will be scaled down. */
    val maxHeight: Int = 2048,
    /** JPEG quality (0.0 to 1.0). Only used when output is JPEG. */
    val quality: Float = 0.85f,
    /** Whether to convert PNG to JPEG for smaller file size (lossy). */
    val convertPngToJpeg: Boolean = true,
    /** Size threshold in bytes. Only resize if image exceeds this size. */
    val sizeThreshold: Long = 1024 * 1024 // 1MB
)

/**
 * Result of image resizing operation.
 */
data class ResizedImage(
    val data: ByteArray,
    val mediaType: String,
    val wasResized: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ResizedImage
        return data.contentEquals(other.data) && mediaType == other.mediaType && wasResized == other.wasResized
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + wasResized.hashCode()
        return result
    }
}

/**
 * Platform-specific image resizer.
 * Resizes images to reduce file size while maintaining quality.
 */
expect object ImageResizer {
    /**
     * Resize an image if it exceeds the configured limits.
     *
     * @param data Original image data
     * @param mediaType Original media type (e.g., "image/png", "image/jpeg")
     * @param config Resize configuration
     * @return ResizedImage with possibly resized data
     */
    fun resize(data: ByteArray, mediaType: String, config: ImageResizeConfig = ImageResizeConfig()): ResizedImage
}
