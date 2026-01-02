package com.claudecode.native.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * iOS implementation of ImagePicker using PHPickerViewController.
 */
actual class ImagePicker actual constructor() {

    // Hold reference to delegate to prevent GC before callback completes
    private var currentDelegate: PHPickerViewControllerDelegateProtocol? = null

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun pickImages(): List<PickedImage> = suspendCancellableCoroutine { continuation ->
        val configuration = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 10 // Allow multiple images
        }

        val picker = PHPickerViewController(configuration = configuration)

        // Store delegate as instance property to prevent GC
        currentDelegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, completion = null)

                if (didFinishPicking.isEmpty()) {
                    currentDelegate = null
                    continuation.resume(emptyList())
                    return
                }

                val results = didFinishPicking.filterIsInstance<PHPickerResult>()
                val pickedImages = mutableListOf<PickedImage>()
                var processedCount = 0
                val totalCount = results.size

                if (totalCount == 0) {
                    currentDelegate = null
                    continuation.resume(emptyList())
                    return
                }

                results.forEach { result ->
                    val itemProvider = result.itemProvider

                    if (itemProvider.hasItemConformingToTypeIdentifier(UTTypeImage.identifier)) {
                        itemProvider.loadDataRepresentationForTypeIdentifier(
                            UTTypeImage.identifier
                        ) { data, error ->
                            // Dispatch to main queue for thread safety
                            dispatch_async(dispatch_get_main_queue()) {
                                if (data != null && error == null) {
                                    val bytes = data.toByteArray()
                                    val mediaType = detectMediaType(data)
                                    val fileName = itemProvider.suggestedName ?: "image"

                                    pickedImages.add(
                                        PickedImage(
                                            data = bytes,
                                            mediaType = mediaType,
                                            fileName = fileName
                                        )
                                    )
                                }

                                processedCount++
                                if (processedCount == totalCount) {
                                    currentDelegate = null
                                    continuation.resume(pickedImages.toList())
                                }
                            }
                        }
                    } else {
                        processedCount++
                        if (processedCount == totalCount) {
                            currentDelegate = null
                            continuation.resume(pickedImages.toList())
                        }
                    }
                }
            }
        }

        picker.delegate = currentDelegate

        // Get root view controller - using keyWindow which works in Kotlin/Native
        // Note: keyWindow is deprecated in iOS 13+ but still functional and simpler for K/N interop
        @Suppress("DEPRECATION")
        val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController

        // Handle case where view controller is not available
        if (rootViewController == null) {
            currentDelegate = null
            continuation.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        rootViewController.presentViewController(picker, animated = true, completion = null)

        continuation.invokeOnCancellation {
            currentDelegate = null
            picker.dismissViewControllerAnimated(true, completion = null)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        val size = this.length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) {
            memcpy(bytes.refTo(0), this.bytes, this.length)
        }
        return bytes
    }

    /**
     * Detect media type by reading only the header bytes (optimized to avoid full copy).
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun detectMediaType(data: NSData): String {
        val length = data.length.toInt()
        if (length < 12) return "application/octet-stream"

        // Only copy header bytes needed for detection (max 12 bytes)
        val headerSize = min(12, length)
        val header = ByteArray(headerSize)
        memcpy(header.refTo(0), data.bytes, headerSize.toULong())

        return when {
            // PNG: 89 50 4E 47
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> "image/png"

            // JPEG: FF D8 FF
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
                    header[2] == 0xFF.toByte() -> "image/jpeg"

            // GIF: 47 49 46 38
            header[0] == 0x47.toByte() && header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() && header[3] == 0x38.toByte() -> "image/gif"

            // WebP: 52 49 46 46 ... 57 45 42 50 (RIFF....WEBP)
            headerSize >= 12 &&
                    header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                    header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                    header[10] == 0x42.toByte() && header[11] == 0x50.toByte() -> "image/webp"

            // BMP: 42 4D
            header[0] == 0x42.toByte() && header[1] == 0x4D.toByte() -> "image/bmp"

            else -> "application/octet-stream"
        }
    }
}
