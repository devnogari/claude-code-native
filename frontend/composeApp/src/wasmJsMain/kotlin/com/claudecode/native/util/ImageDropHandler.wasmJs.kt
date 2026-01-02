package com.claudecode.native.util

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.dom.DragEvent
import org.w3c.dom.events.Event
import org.w3c.files.File
import org.w3c.files.FileReader

/**
 * WASM (Browser) implementation of image drop target using HTML5 Drag and Drop API.
 */
@Composable
actual fun Modifier.imageDropTarget(
    enabled: Boolean,
    onDragStateChange: (DropState) -> Unit,
    onImageDropped: (List<PickedImage>) -> Unit
): Modifier {
    if (!enabled) return this

    // We need to handle drops at the document level since Compose WASM
    // doesn't have direct access to DOM events on its canvas
    DisposableEffect(enabled, onDragStateChange, onImageDropped) {
        val canvas = document.querySelector("canvas")

        val handleDragOver: (Event) -> Unit = { e ->
            e.preventDefault()
            e.stopPropagation()
            onDragStateChange(DropState(isDragging = true, isHovering = true))
        }

        val handleDragEnter: (Event) -> Unit = { e ->
            e.preventDefault()
            e.stopPropagation()
            onDragStateChange(DropState(isDragging = true, isHovering = true))
        }

        val handleDragLeave: (Event) -> Unit = { e ->
            e.preventDefault()
            e.stopPropagation()
            // Only set hovering to false if we're leaving the canvas
            val dragEvent = e as? DragEvent
            val relatedTarget = dragEvent?.relatedTarget
            if (relatedTarget == null || relatedTarget != canvas) {
                onDragStateChange(DropState(isDragging = true, isHovering = false))
            }
        }

        val handleDrop: (Event) -> Unit = { e ->
            e.preventDefault()
            e.stopPropagation()
            onDragStateChange(DropState(isDragging = false, isHovering = false))

            val dragEvent = e as? DragEvent
            val files = dragEvent?.dataTransfer?.files
            if (files != null && files.length > 0) {
                val imageFiles = mutableListOf<File>()
                for (i in 0 until files.length) {
                    val file = files.item(i)
                    if (file != null && file.type.startsWith("image/")) {
                        imageFiles.add(file)
                    }
                }

                if (imageFiles.isNotEmpty()) {
                    processDroppedFiles(imageFiles, onImageDropped)
                }
            }
        }

        val handleDragEnd: (Event) -> Unit = { e ->
            e.preventDefault()
            onDragStateChange(DropState(isDragging = false, isHovering = false))
        }

        // Add listeners to canvas (or document body if canvas not found)
        val target = canvas ?: document.body

        target?.addEventListener("dragover", handleDragOver)
        target?.addEventListener("dragenter", handleDragEnter)
        target?.addEventListener("dragleave", handleDragLeave)
        target?.addEventListener("drop", handleDrop)
        target?.addEventListener("dragend", handleDragEnd)

        // Document-level handlers to prevent default browser behavior
        // and catch drops that miss the canvas
        val documentDragOver: (Event) -> Unit = { e -> e.preventDefault() }
        val documentDrop: (Event) -> Unit = { e ->
            e.preventDefault()
            // Forward to canvas drop handler
            if (target != document) {
                handleDrop(e)
            }
        }

        document.addEventListener("dragover", documentDragOver)
        document.addEventListener("drop", documentDrop)

        onDispose {
            target?.removeEventListener("dragover", handleDragOver)
            target?.removeEventListener("dragenter", handleDragEnter)
            target?.removeEventListener("dragleave", handleDragLeave)
            target?.removeEventListener("drop", handleDrop)
            target?.removeEventListener("dragend", handleDragEnd)
            // Clean up document-level listeners to prevent memory leaks
            document.removeEventListener("dragover", documentDragOver)
            document.removeEventListener("drop", documentDrop)
        }
    }

    return this
}

private fun processDroppedFiles(files: List<File>, onImageDropped: (List<PickedImage>) -> Unit) {
    val images = mutableListOf<PickedImage>()
    var processedCount = 0

    files.forEach { file ->
        val reader = FileReader()
        reader.onload = { event ->
            val result = reader.result
            if (result != null) {
                // Result is an ArrayBuffer, convert to ByteArray
                val arrayBuffer = result.unsafeCast<ArrayBuffer>()
                val uint8Array = Uint8Array(arrayBuffer)
                val byteArray = ByteArray(uint8Array.length) { idx -> uint8Array[idx] }

                images.add(
                    PickedImage(
                        data = byteArray,
                        mediaType = file.type.ifEmpty { getMediaTypeFromName(file.name) },
                        fileName = file.name
                    )
                )
            }
            processedCount++
            if (processedCount == files.size && images.isNotEmpty()) {
                onImageDropped(images)
            }
            Unit
        }
        reader.onerror = {
            processedCount++
            if (processedCount == files.size && images.isNotEmpty()) {
                onImageDropped(images)
            }
            Unit
        }
        reader.readAsArrayBuffer(file)
    }
}

private fun getMediaTypeFromName(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }
}
