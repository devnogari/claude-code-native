package com.claudecode.native.util

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files

/**
 * Desktop (JVM) implementation of image drop target using Compose's official
 * dragAndDropTarget modifier API (available in Compose Multiplatform 1.7.0+).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.imageDropTarget(
    enabled: Boolean,
    onDragStateChange: (DropState) -> Unit,
    onImageDropped: (List<PickedImage>) -> Unit
): Modifier {
    if (!enabled) {
        return this
    }

    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val currentOnImageDropped by rememberUpdatedState(onImageDropped)

    val dragAndDropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                currentOnDragStateChange(DropState(isDragging = true, isHovering = false))
            }

            override fun onEntered(event: DragAndDropEvent) {
                currentOnDragStateChange(DropState(isDragging = true, isHovering = true))
            }

            override fun onExited(event: DragAndDropEvent) {
                currentOnDragStateChange(DropState(isDragging = true, isHovering = false))
            }

            override fun onEnded(event: DragAndDropEvent) {
                currentOnDragStateChange(DropState(isDragging = false, isHovering = false))
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transferable = event.awtTransferable

                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    return false
                }

                try {
                    @Suppress("UNCHECKED_CAST")
                    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>

                    val images = files.filter { isImageFile(it) }.mapNotNull { file ->
                        try {
                            val data = file.readBytes()
                            val mediaType = Files.probeContentType(file.toPath())
                                ?: getMediaTypeFromExtension(file.extension)
                            PickedImage(
                                data = data,
                                mediaType = mediaType,
                                fileName = file.name
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (images.isNotEmpty()) {
                        currentOnImageDropped(images)
                        return true
                    }
                } catch (e: Exception) {
                    // Failed to process drop
                }

                return false
            }
        }
    }

    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        },
        target = dragAndDropTarget
    )
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
private val IMAGE_MIME_TYPES = setOf(
    "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp"
)

/**
 * Validates if a file is an image using MIME type detection.
 * Falls back to extension check if MIME type cannot be determined.
 */
private fun isImageFile(file: File): Boolean {
    try {
        val mimeType = Files.probeContentType(file.toPath())
        if (mimeType != null) {
            return mimeType in IMAGE_MIME_TYPES
        }
    } catch (e: Exception) {
        // Fallback to extension check
    }
    return file.extension.lowercase() in IMAGE_EXTENSIONS
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
