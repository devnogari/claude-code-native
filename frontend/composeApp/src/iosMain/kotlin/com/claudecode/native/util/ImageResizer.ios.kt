package com.claudecode.native.util

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy
import kotlin.math.min

/**
 * iOS implementation of ImageResizer using UIKit.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object ImageResizer {

    actual fun resize(data: ByteArray, mediaType: String, config: ImageResizeConfig): ResizedImage {
        // Skip if below size threshold
        if (data.size < config.sizeThreshold) {
            return ResizedImage(data, mediaType, wasResized = false)
        }

        // Convert ByteArray to NSData
        val nsData = data.toNSData()

        // Create UIImage from data
        val originalImage = UIImage.imageWithData(nsData)
            ?: return ResizedImage(data, mediaType, wasResized = false)

        val originalWidth: Double
        val originalHeight: Double
        originalImage.size.useContents {
            originalWidth = width
            originalHeight = height
        }

        // Calculate scale factor to fit within max dimensions
        val widthRatio = config.maxWidth.toDouble() / originalWidth
        val heightRatio = config.maxHeight.toDouble() / originalHeight
        val scaleFactor = min(1.0, min(widthRatio, heightRatio))

        // If no scaling needed and not converting format, return original
        val needsScaling = scaleFactor < 1.0
        val needsConversion = config.convertPngToJpeg && mediaType == "image/png"

        if (!needsScaling && !needsConversion) {
            return ResizedImage(data, mediaType, wasResized = false)
        }

        // Calculate new dimensions
        val newWidth = (originalWidth * scaleFactor).toInt()
        val newHeight = (originalHeight * scaleFactor).toInt()

        // Determine output format
        val outputAsJpeg = needsConversion || mediaType == "image/jpeg"
        val outputMediaType = if (outputAsJpeg) "image/jpeg" else mediaType

        // Resize the image (with white background for JPEG to handle transparency)
        val resizedImage = if (needsScaling) {
            resizeImage(originalImage, newWidth, newHeight, opaqueBackground = outputAsJpeg)
        } else if (outputAsJpeg) {
            // Need to add white background even without scaling for JPEG conversion
            resizeImage(originalImage, originalWidth.toInt(), originalHeight.toInt(), opaqueBackground = true)
        } else {
            originalImage
        } ?: return ResizedImage(data, mediaType, wasResized = false)

        // Compress to target format
        val outputData = if (outputAsJpeg) {
            UIImageJPEGRepresentation(resizedImage, config.quality.toDouble())
        } else {
            UIImagePNGRepresentation(resizedImage)
        } ?: return ResizedImage(data, mediaType, wasResized = false)

        val outputBytes = outputData.toByteArray()

        // Only use resized if it's actually smaller
        return if (outputBytes.size < data.size) {
            ResizedImage(outputBytes, outputMediaType, wasResized = true)
        } else {
            ResizedImage(data, mediaType, wasResized = false)
        }
    }

    private fun resizeImage(
        image: UIImage,
        targetWidth: Int,
        targetHeight: Int,
        opaqueBackground: Boolean = false
    ): UIImage? {
        val size = CGSizeMake(targetWidth.toDouble(), targetHeight.toDouble())

        // opaque = true for JPEG (fills with white), false for PNG (keeps transparency)
        UIGraphicsBeginImageContextWithOptions(size, opaqueBackground, 1.0)

        // If opaque, fill with white background first (for JPEG transparency handling)
        if (opaqueBackground) {
            val context = UIGraphicsGetCurrentContext()
            if (context != null) {
                UIColor.whiteColor.setFill()
                platform.CoreGraphics.CGContextFillRect(
                    context,
                    CGRectMake(0.0, 0.0, targetWidth.toDouble(), targetHeight.toDouble())
                )
            }
        }

        image.drawInRect(CGRectMake(0.0, 0.0, targetWidth.toDouble(), targetHeight.toDouble()))

        val resizedImage = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()

        return resizedImage
    }

    private fun ByteArray.toNSData(): NSData {
        if (this.isEmpty()) return NSData()
        return this.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
        }
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = this.length.toInt()
        if (size == 0) return ByteArray(0)
        val bytes = ByteArray(size)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), this.bytes, this.length)
        }
        return bytes
    }
}
