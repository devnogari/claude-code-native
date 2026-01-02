package com.claudecode.native.util

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.io.File
import java.nio.file.Files

/**
 * Desktop (JVM) implementation of image drop target using AWT DropTarget.
 *
 * Note: This uses a global drop target on the window since Compose Multiplatform
 * doesn't yet have a stable Modifier-based drag and drop API. The entire window
 * acts as a drop zone when enabled.
 */
@Composable
actual fun Modifier.imageDropTarget(
    enabled: Boolean,
    onDragStateChange: (DropState) -> Unit,
    onImageDropped: (List<PickedImage>) -> Unit
): Modifier {
    // Use rememberUpdatedState to capture latest callbacks without re-triggering DisposableEffect
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val currentOnImageDropped by rememberUpdatedState(onImageDropped)

    DisposableEffect(enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }

        // Find all frames and add drop target to each
        val frames = java.awt.Frame.getFrames()
        val dropTargets = mutableListOf<DropTarget>()

        val dropTargetListener = object : DropTargetListener {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (isImageDrag(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                    currentOnDragStateChange(DropState(isDragging = true, isHovering = true))
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dragOver(dtde: DropTargetDragEvent) {
                if (isImageDrag(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                }
            }

            override fun dropActionChanged(dtde: DropTargetDragEvent) {}

            override fun dragExit(dte: DropTargetEvent) {
                currentOnDragStateChange(DropState(isDragging = true, isHovering = false))
            }

            override fun drop(dtde: DropTargetDropEvent) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY)
                    val transferable = dtde.transferable

                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @Suppress("UNCHECKED_CAST")
                        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>

                        val images = files.filter { isImageFile(it) }.mapNotNull { file ->
                            try {
                                val data = file.readBytes()
                                // Use probed content type if available, fallback to extension
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
                            dtde.dropComplete(true)
                        } else {
                            dtde.dropComplete(false)
                        }
                    } else {
                        dtde.dropComplete(false)
                    }
                } catch (e: Exception) {
                    dtde.dropComplete(false)
                } finally {
                    currentOnDragStateChange(DropState(isDragging = false, isHovering = false))
                }
            }

            private fun isImageDrag(dtde: DropTargetDragEvent): Boolean {
                return dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            }
        }

        frames.forEach { frame ->
            val dropTarget = DropTarget(frame, DnDConstants.ACTION_COPY, dropTargetListener, true)
            dropTargets.add(dropTarget)
        }

        onDispose {
            dropTargets.forEach { it.removeDropTargetListener(dropTargetListener) }
            dropTargets.clear()
        }
    }

    return this
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
    // First try MIME type detection (more reliable than extension)
    try {
        val mimeType = Files.probeContentType(file.toPath())
        if (mimeType != null) {
            return mimeType in IMAGE_MIME_TYPES
        }
    } catch (e: Exception) {
        // Fallback to extension check
    }
    // Fallback to extension check
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
