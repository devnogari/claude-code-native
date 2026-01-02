package com.claudecode.native.util

import com.claudecode.native.ui.viewmodel.AttachedImage

/**
 * Platform result for image picking.
 */
data class PickedImage(
    val data: ByteArray,
    val mediaType: String,
    val fileName: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as PickedImage
        return data.contentEquals(other.data) && mediaType == other.mediaType && fileName == other.fileName
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + (fileName?.hashCode() ?: 0)
        return result
    }
}

/**
 * Platform-specific image picker.
 * Opens the system file picker dialog for selecting images.
 */
expect class ImagePicker() {
    /**
     * Open file picker and return selected images.
     * Returns empty list if user cancels or no images selected.
     */
    suspend fun pickImages(): List<PickedImage>
}

/**
 * Convert PickedImage to AttachedImage.
 */
fun PickedImage.toAttachedImage(id: String): AttachedImage {
    return AttachedImage(
        id = id,
        data = data,
        mediaType = mediaType,
        fileName = fileName
    )
}
