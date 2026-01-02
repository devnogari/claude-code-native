@file:OptIn(ExperimentalWasmJsInterop::class)

package com.claudecode.native.util

import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.Image
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.math.min

/**
 * WASM implementation of ImageResizer using Canvas API.
 *
 * Note: The sync `resize()` function returns original data because
 * Canvas API requires async operations. Use `resizeAsync()` for actual resizing,
 * which is called automatically by ImagePicker.
 */
actual object ImageResizer {

    /**
     * Synchronous resize - returns original for WASM since Canvas API is async.
     * ImagePicker should use resizeAsync() instead.
     */
    actual fun resize(data: ByteArray, mediaType: String, config: ImageResizeConfig): ResizedImage {
        // Canvas API is inherently async, so sync version just returns original
        // The actual resizing happens in resizeAsync() called by ImagePicker
        return ResizedImage(data, mediaType, wasResized = false)
    }

    /**
     * Async version that performs actual resizing using Canvas API.
     * Should be called from ImagePicker after loading image data.
     */
    suspend fun resizeAsync(
        data: ByteArray,
        mediaType: String,
        config: ImageResizeConfig = ImageResizeConfig()
    ): ResizedImage {
        // Skip if below size threshold
        if (data.size < config.sizeThreshold) {
            return ResizedImage(data, mediaType, wasResized = false)
        }

        return try {
            // Create Blob from ByteArray
            val uint8Array = byteArrayToUint8Array(data)
            val blobParts = jsArrayOf(uint8Array)
            val blob = Blob(blobParts, BlobPropertyBag(type = mediaType))
            val imageUrl = URL.createObjectURL(blob)

            try {
                // Load image
                val img = loadImageAsync(imageUrl)

                val originalWidth = img.naturalWidth
                val originalHeight = img.naturalHeight

                // Calculate scale factor
                val widthRatio = config.maxWidth.toDouble() / originalWidth
                val heightRatio = config.maxHeight.toDouble() / originalHeight
                val scaleFactor = min(1.0, min(widthRatio, heightRatio))

                val needsScaling = scaleFactor < 1.0
                val needsConversion = config.convertPngToJpeg && mediaType == "image/png"

                if (!needsScaling && !needsConversion) {
                    return ResizedImage(data, mediaType, wasResized = false)
                }

                // Calculate new dimensions
                val newWidth = (originalWidth * scaleFactor).toInt()
                val newHeight = (originalHeight * scaleFactor).toInt()

                // Create canvas and draw resized image
                val canvas = document.createElement("canvas") as HTMLCanvasElement
                canvas.width = newWidth
                canvas.height = newHeight

                val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
                ctx.drawImage(img, 0.0, 0.0, newWidth.toDouble(), newHeight.toDouble())

                // Determine output format
                val outputAsJpeg = needsConversion || mediaType == "image/jpeg"
                val outputMediaType = if (outputAsJpeg) "image/jpeg" else mediaType
                val quality = if (outputAsJpeg) config.quality.toDouble() else 1.0

                // Convert canvas to data URL (synchronous) and then to ByteArray
                val dataUrl = toDataURL(canvas, outputMediaType, quality)
                val outputBytes = decodeDataUrl(dataUrl)

                // Only use resized if actually smaller
                if (outputBytes.size < data.size) {
                    ResizedImage(outputBytes, outputMediaType, wasResized = true)
                } else {
                    ResizedImage(data, mediaType, wasResized = false)
                }
            } finally {
                URL.revokeObjectURL(imageUrl)
            }
        } catch (e: Exception) {
            // On any error, return original
            ResizedImage(data, mediaType, wasResized = false)
        }
    }

    private fun byteArrayToUint8Array(data: ByteArray): Uint8Array {
        val uint8Array = Uint8Array(data.size)
        for (i in data.indices) {
            uint8Array[i] = data[i]
        }
        return uint8Array
    }

    private suspend fun loadImageAsync(url: String): HTMLImageElement = suspendCancellableCoroutine { cont ->
        val img = Image()

        img.onload = {
            cont.resume(img)
            Unit
        }

        img.onerror = { _, _, _, _, _ ->
            cont.resumeWithException(Exception("Failed to load image"))
            null
        }

        img.src = url

        cont.invokeOnCancellation {
            img.src = ""
        }
    }

    /**
     * Convert data URL to ByteArray.
     * Data URL format: "data:image/jpeg;base64,/9j/4AAQ..."
     */
    private fun decodeDataUrl(dataUrl: String): ByteArray {
        // Extract base64 part after the comma
        val base64Index = dataUrl.indexOf(",")
        if (base64Index == -1) {
            throw Exception("Invalid data URL format")
        }
        val base64Data = dataUrl.substring(base64Index + 1)

        // Decode base64 using JavaScript's atob and convert to ByteArray
        return base64ToByteArray(base64Data)
    }
}

// Top-level JS interop functions (required by Kotlin/WASM)
// These must be package-level functions with explicit return types

@Suppress("UNUSED_PARAMETER")
private fun jsArrayOf(element: Uint8Array): JsArray<JsAny?> = js("([element])")

@Suppress("UNUSED_PARAMETER")
private fun toDataURL(canvas: HTMLCanvasElement, mimeType: String, quality: Double): String =
    js("canvas.toDataURL(mimeType, quality)")

@Suppress("UNUSED_PARAMETER")
private fun base64ToByteArray(base64: String): ByteArray {
    val binaryString = atob(base64)
    val length = binaryStringLength(binaryString)
    val bytes = ByteArray(length)
    for (i in 0 until length) {
        bytes[i] = binaryStringCharCodeAt(binaryString, i).toByte()
    }
    return bytes
}

@Suppress("UNUSED_PARAMETER")
private fun atob(base64: String): JsAny = js("atob(base64)")

@Suppress("UNUSED_PARAMETER")
private fun binaryStringLength(str: JsAny): Int = js("str.length")

@Suppress("UNUSED_PARAMETER")
private fun binaryStringCharCodeAt(str: JsAny, index: Int): Int = js("str.charCodeAt(index)")
