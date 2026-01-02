package com.claudecode.native.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

/**
 * Desktop (JVM) implementation of ImagePicker using AWT FileDialog.
 */
actual class ImagePicker actual constructor() {

    actual suspend fun pickImages(): List<PickedImage> = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Select Images", FileDialog.LOAD).apply {
            isMultipleMode = true
            filenameFilter = FilenameFilter { _, name ->
                val lower = name.lowercase()
                lower.endsWith(".png") ||
                        lower.endsWith(".jpg") ||
                        lower.endsWith(".jpeg") ||
                        lower.endsWith(".gif") ||
                        lower.endsWith(".webp") ||
                        lower.endsWith(".bmp")
            }
        }

        dialog.isVisible = true

        val files = dialog.files ?: return@withContext emptyList()
        if (files.isEmpty()) return@withContext emptyList()

        files.mapNotNull { file ->
            try {
                val data = file.readBytes()
                val mediaType = getMediaTypeFromExtension(file.extension)
                PickedImage(
                    data = data,
                    mediaType = mediaType,
                    fileName = file.name
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun getMediaTypeFromExtension(ext: String): String {
        return when (ext.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
    }
}
